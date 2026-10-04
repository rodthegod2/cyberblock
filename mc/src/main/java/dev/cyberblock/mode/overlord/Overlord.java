package dev.cyberblock.mode.overlord;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ExplosionEvents;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Overlord (megaplan 8-O1), server side. A player whose match role is OVERLORD (from {@code HoloHooks.match}, which 8M fills)
 * opens the Overlord Console (the block, the item, or {@code /cyberblock overlord console}) and spends the match's Threat Points on
 * {@code spawn_palette} entries and {@code event}s at points of the match area. With no human Overlord online, the Auto-Director
 * spends them. Every request passes {@link Fairness} (only players in the running match who opted in with
 * {@code /cyberblock overlord optin on}, a minimum distance, a cap, the match area) and lands after its warning (3 s at least: a sound
 * and a particle marker at the spot, and an action-bar line for the players it is aimed at). Night City enemies aimed at an opted-in
 * host whose {@code spawns} toggle allows it go to that host's game as {@code spawn} DOWN, checked at runtime per host ({@link NcSpawns});
 * otherwise, and near Minecraft-only players, they are Minecraft squads. No host game gets anything from the Overlord without that
 * consent ({@link HostRules}); its mobs and blasts hurt only the match's opted-in players and break no blocks. Server thread only.
 *
 * <p>Log lines: {@code overlord: ready (...)}, {@code overlord: <name> opened the console (<where>, match <id>)},
 * {@code overlord: <who> queued <spawn|event> <id> at <x> <z> (match <id>)}, {@code overlord: <spawn|event> <id> landed (<how>)},
 * {@code overlord: Night City spawns for <host>: ...} (which path is active), {@code overlord: match <id> ended (N spawns removed)}.
 */
public final class Overlord {
	/** Every Minecraft mob the Overlord spawned carries this entity tag (a leftover after a restart is removed when it loads). */
	public static final String TAG = "cyberblock_overlord";
	static final double WHO_RADIUS = 160.0;
	static final int START_POINTS = 20;
	static final long CONSOLE_MS = 3_000L, PACE_MS = 15_000L, ACT_MS = 400L, IDLE_MS = 30_000L;
	/** {@code ovlpoll}: an Overlord's console polls at most this often; anyone else gets the "not an Overlord" answer at most this often. */
	static final long POLL_MS = 250L, NOT_OVERLORD_POLL_MS = 5_000L;
	static final int DIRECTOR_TICKS = 100;
	static final int TIMELINE = 24;

	/** One running match's Overlord state. */
	static final class MatchState {
		final String id;
		final ThreatPoints points = new ThreatPoints(START_POINTS);
		Intensity intensity = Intensity.NORMAL;
		final Deque<long[]> timelineAt = new ArrayDeque<>();
		final Deque<String> timeline = new ArrayDeque<>();
		final List<Pending> pending = new ArrayList<>();
		/** Minecraft mobs alive, by entity UUID. */
		final Set<UUID> mobs = new HashSet<>();
		/** Night City spawns believed alive: request key to [expiry ms, count]. */
		final Map<String, long[]> nightCity = new HashMap<>();
		/** Entry cooldowns: "s:<id>" or "e:<id>" to the ms it is ready again. */
		final Map<String, Long> cooldowns = new HashMap<>();
		/** Supply-drop eddies paid this match, in all and per player (capped by {@link Fairness#eddieShares}). */
		int eddiesPaid;
		final Map<UUID, Integer> eddiesTo = new HashMap<>();
		long seen = System.currentTimeMillis();
		long lastDirector = System.currentTimeMillis();
		boolean director;

		MatchState(final String id) {
			this.id = id;
		}

		int live() {
			int n = this.mobs.size();
			for (long[] v : this.nightCity.values()) {
				n += (int) v[1];
			}

			for (Pending p : this.pending) {
				n += p.units();
			}

			return n;
		}

		void line(final String text) {
			this.timeline.addFirst(text);
			this.timelineAt.addFirst(new long[] {System.currentTimeMillis()});
			while (this.timeline.size() > TIMELINE) {
				this.timeline.removeLast();
				this.timelineAt.removeLast();
			}
		}
	}

	/**
	 * A queued spawn or event waiting for its warning to end. {@code by}: the human Overlord (null for the Auto-Director); {@code units}:
	 * mobs it adds (for the cap).
	 */
	record Pending(boolean event, Identifier id, String name, ServerLevel level, Vec3 at, long due, int units, int radius, @Nullable UUID by,
		String byName, int cost) {
	}

	private static final Map<String, MatchState> matches = new LinkedHashMap<>();
	private static final Map<UUID, String> optIn = new HashMap<>();
	private static final Map<UUID, Long> hurt = new HashMap<>();
	private static final Map<UUID, Long> lastAct = new HashMap<>();
	private static final Map<UUID, Long> lastPoll = new HashMap<>();
	private static final Set<String> told = new HashSet<>();
	static final NcSpawns nightCity = new NcSpawns(Cyberblock.LOG::info);
	private static final Random random = new Random();
	private static @Nullable MinecraftServer server;

	private Overlord() {
	}

	/** Common init (Cyberblock.onInitialize): content kinds, the console block and item, acts, listeners, commands and the tick. */
	public static void init() {
		OverlordContent.register();
		OverlordBlocks.register();
		ServerNet.onAct(OverlordWire.T_POLL, (player, m) -> {
			if (due(lastPoll, player.getUUID(), System.currentTimeMillis(), isOverlord(player) ? POLL_MS : NOT_OVERLORD_POLL_MS)) {
				poll(player, false, "");
			}
		});
		ServerNet.onAct(OverlordWire.T_SPAWN, (player, m) -> onAsk(player, m, false));
		ServerNet.onAct(OverlordWire.T_EVENT, (player, m) -> onAsk(player, m, true));
		HostSessions.listen("spawnresult", OverlordWorld::onSpawnResult);
		HostSessions.onStart(s -> nightCity.reset(s.uuid()));
		HostSessions.onEnd(s -> nightCity.reset(s.uuid()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			UUID id = handler.getPlayer().getUUID();
			lastAct.remove(id);
			lastPoll.remove(id);
			hurt.remove(id);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowDamage(entity, source.getEntity(), source.getDirectEntity()));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayer p && taken > 0.0F) {
				hurt.put(p.getUUID(), System.currentTimeMillis());
			}
		});
		// the Overlord's airstrikes (an OverlordWorld.Blast) and its mobs' blasts: no broken blocks, no bystanders, consenting hosts only
		ExplosionEvents.owner = (source, damage) -> {
			OverlordWorld.Blast blast = damage instanceof OverlordWorld.Blast b ? b : null;
			Entity mob = source == null ? null : tagged(source) ? source : Explosion.getIndirectSourceEntity(source);
			if (blast == null && mob != null && tagged(mob)) {
				String match = matchOfMob(mob.getUUID());
				blast = new OverlordWorld.Blast(match == null ? "" : match, null, true);
			}

			OverlordWorld.Blast owned = blast;
			return owned == null ? null : new ExplosionEvents.Owned(Level.ExplosionInteraction.NONE, owned, owned::hears);
		};
		// a mob of an earlier run (saved with its chunk) belongs to no match any more
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.entityTags().contains(TAG) && matches.values().stream().noneMatch(m -> m.mobs.contains(entity.getUUID()))) {
				entity.discard();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(Overlord::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			Cyberblock.LOG.info(OverlordHooks.running == OverlordHooks.NOT_SET
				? "overlord: no match hook is set (OverlordHooks.running, 8M): no match counts as running, the Overlord stays off"
				: "overlord: match hooks: running set, area " + (OverlordHooks.area == OverlordHooks.NO_AREA ? "the box around the players" : "set")
					+ ", heat " + (OverlordHooks.heat == OverlordHooks.NO_HEAT ? "0 (not set)" : "set"));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			matches.values().forEach(m -> OverlordWorld.removeMobs(s, m));
			matches.clear();
			optIn.clear();
			hurt.clear();
			lastAct.clear();
			lastPoll.clear();
			told.clear();
			server = null;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			commands(dispatcher);
			OverlordTestCommand.register(dispatcher);
		});
		Cyberblock.LOG.info("overlord: ready (spawn_palette and event content; Night City spawns go as spawn DOWN with a runtime check per host, "
			+ "Minecraft squads are the fallback and the path near Minecraft-only players)");
	}

	// ---- who is who ----

	/** The running match this player is in (any role), or null. */
	static @Nullable String matchOf(final ServerPlayer p) {
		HoloHooks.Match m = HoloHooks.match.apply(p);
		return m.id() != null && OverlordHooks.running.test(m.id()) ? m.id() : null;
	}

	static boolean isOverlord(final ServerPlayer p) {
		return matchOf(p) != null && HoloHooks.match.apply(p).role() == HoloRules.Role.OVERLORD;
	}

	/** A player of the match (not its Overlord or a spectator). */
	static boolean participant(final ServerPlayer p, final String match) {
		return match.equals(matchOf(p)) && HoloHooks.match.apply(p).role() == HoloRules.Role.PLAYER;
	}

	/** A player of the match who opted in: the only players the Overlord may touch. */
	static boolean eligible(final ServerPlayer p, final String match) {
		return participant(p, match) && match.equals(optIn.get(p.getUUID()));
	}

	static boolean tagged(final @Nullable Entity e) {
		return e != null && e.entityTags().contains(TAG);
	}

	/** The match whose Overlord spawned this mob, or null. */
	static @Nullable String matchOfMob(final UUID mob) {
		for (MatchState m : matches.values()) {
			if (m.mobs.contains(mob)) {
				return m.id;
			}
		}

		return null;
	}

	/** May an Overlord blast of this match hurt or push the entity? Its opted-in players, Overlord mobs and monsters only. */
	static boolean mayHit(final Entity entity, final String match) {
		if (entity instanceof ServerPlayer p) {
			return eligible(p, match);
		}

		return tagged(entity) || entity instanceof Enemy;
	}

	/**
	 * ALLOW_DAMAGE: an Overlord mob (the attacker or its projectile carries the tag) hurts only the opted-in players of its match, and no
	 * armour stand; everything else is not the Overlord's business.
	 */
	static boolean allowDamage(final LivingEntity victim, final @Nullable Entity attacker, final @Nullable Entity direct) {
		Entity mob = tagged(attacker) ? attacker : tagged(direct) ? direct : null;
		if (mob == null) {
			return true;
		}

		if (victim instanceof ServerPlayer p) {
			String match = matchOfMob(mob.getUUID());
			return match != null && eligible(p, match);
		}

		return !(victim instanceof ArmorStand);
	}

	/** A rate limit: true (and now noted) when the player's last noted time is at least {@code minMs} ago or there is none. */
	static boolean due(final Map<UUID, Long> last, final UUID player, final long now, final long minMs) {
		Long t = last.get(player);
		if (t != null && now - t < minMs) {
			return false;
		}

		last.put(player, now);
		return true;
	}

	/** The match's players as fairness sees them. */
	static List<Fairness.Who> who(final MinecraftServer s, final String match) {
		List<Fairness.Who> out = new ArrayList<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (participant(p, match)) {
				out.add(new Fairness.Who(p.getPlainTextName(), p.getX(), p.getZ(), eligible(p, match)));
			}
		}

		return out;
	}

	/** The match area: the match's own zone (8M) or the box around its players. */
	static Fairness.@Nullable Area area(final MinecraftServer s, final String match) {
		double[] a = OverlordHooks.area.apply(match);
		if (a != null && a.length == 4) {
			return new Fairness.Area(Math.min(a[0], a[2]), Math.min(a[1], a[3]), Math.max(a[0], a[2]), Math.max(a[1], a[3]));
		}

		return Fairness.Area.around(who(s, match), Fairness.AREA_MARGIN);
	}

	/** Match pace, 0..1: the share of its opted-in players hurt in the last 15 s, or how full its spawn cap is, whichever is more. */
	static double pace(final MinecraftServer s, final MatchState m, final long now) {
		int eligible = 0, hurtNow = 0;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (eligible(p, m.id)) {
				eligible++;
				Long t = hurt.get(p.getUUID());
				if (t != null && now - t <= PACE_MS) {
					hurtNow++;
				}
			}
		}

		double fighting = eligible == 0 ? 0.0 : (double) hurtNow / eligible;
		return Math.max(fighting, Math.min(1.0, (double) m.live() / m.intensity.cap()));
	}

	// ---- the console ----

	/** The console was opened (block, item or command): the first state, with open, if the player is an Overlord. */
	static void open(final ServerPlayer player, final String where) {
		String match = matchOf(player);
		if (!isOverlord(player) || match == null) {
			player.sendSystemMessage(Component.translatableWithFallback("cyberblock.overlord.not_overlord",
				"Only a match's Overlord can use the console: join a running match as Overlord at the Match Terminal"));
			return;
		}

		Cyberblock.LOG.info("overlord: {} opened the console ({}, match {})", player.getPlainTextName(), where, match);
		poll(player, true, "");
	}

	/** Sends the console state to an Overlord (the answer to every poll and request). */
	static void poll(final ServerPlayer player, final boolean open, final String note) {
		MinecraftServer s = player.level().getServer();
		String match = matchOf(player);
		if (match == null || !isOverlord(player)) {
			ServerNet.ui(player, OverlordWire.UI_STATE, OverlordWire.state(new OverlordWire.State(false, "", 0, 0, 0.0, "", 0, 0, 0, false, null,
				List.of(), List.of(), List.of(), List.of(), List.of(), "Not an Overlord of a running match")));
			return;
		}

		MatchState m = state(match);
		ServerNet.ui(player, OverlordWire.UI_STATE, OverlordWire.state(view(s, m, open, note)));
	}

	static MatchState state(final String match) {
		return matches.computeIfAbsent(match, MatchState::new);
	}

	/** Is this match still running here (not ended since)? */
	static boolean active(final MatchState m) {
		return matches.get(m.id) == m;
	}

	/** The console's view of one match. */
	static OverlordWire.State view(final MinecraftServer s, final MatchState m, final boolean open, final String note) {
		long now = System.currentTimeMillis();
		List<OverlordWire.Player> players = new ArrayList<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (participant(p, m.id)) {
				players.add(new OverlordWire.Player(p.getPlainTextName(), p.getX(), p.getZ(), eligible(p, m.id), HostSessions.isHost(p.getUUID())));
			}
		}

		int live = m.live();
		List<OverlordWire.Option> spawns = new ArrayList<>();
		OverlordContent.spawns().forEach((id, e) -> {
			int wait = waitS(m, "s:" + id, now);
			int units = Math.max(e.count(), e.squadCount());
			spawns.add(new OverlordWire.Option(id.toString(), e.name(), e.description(), e.group(), e.nightCity(), e.cost(), e.count(), wait,
				wait == 0 && m.points.canAfford(e.cost()) && live + units <= m.intensity.cap()));
		});
		spawns.sort(Comparator.comparing(OverlordWire.Option::group).thenComparingInt(OverlordWire.Option::cost));
		List<OverlordWire.Option> events = new ArrayList<>();
		OverlordContent.events().forEach((id, e) -> {
			int wait = waitS(m, "e:" + id, now);
			events.add(new OverlordWire.Option(id.toString(), e.name(), e.description(), e.effect().type(), e.nightCity(), e.cost(), e.effect().count(),
				wait, wait == 0 && m.points.canAfford(e.cost())));
		});
		events.sort(Comparator.comparingInt(OverlordWire.Option::cost));
		long tick = s.getTickCount();
		List<OverlordWire.Mark> pending = m.pending.stream().map(p -> new OverlordWire.Mark(p.event() ? "event" : "spawn", p.name(), p.at().x, p.at().z,
			(int) Math.max(0, p.due() - tick), p.radius())).toList();
		List<OverlordWire.Line> timeline = new ArrayList<>();
		Iterator<long[]> at = m.timelineAt.iterator();
		for (String text : m.timeline) {
			timeline.add(new OverlordWire.Line((int) ((now - at.next()[0]) / 1000L), text));
		}

		Fairness.Area a = area(s, m.id);
		return new OverlordWire.State(open, m.id, m.points.whole(), m.intensity.maxPoints(), m.intensity.regen(), m.intensity.key(), live,
			m.intensity.cap(), (int) m.intensity.minDistance(), m.director, a == null ? null : new double[] {a.x0(), a.z0(), a.x1(), a.z1()}, players,
			spawns, events, pending, timeline, note);
	}

	private static int waitS(final MatchState m, final String key, final long now) {
		Long until = m.cooldowns.get(key);
		return until == null || until <= now ? 0 : (int) Math.ceil((until - now) / 1000.0);
	}

	private static void onAsk(final ServerPlayer player, final JsonObject json, final boolean event) {
		long now = System.currentTimeMillis();
		Long last = lastAct.get(player.getUUID());
		if (last != null && now - last < ACT_MS) {
			return;
		}

		lastAct.put(player.getUUID(), now);
		OverlordWire.Ask ask = OverlordWire.parseAsk(json);
		String match = matchOf(player);
		Identifier id = ask == null ? null : Identifier.tryParse(ask.id());
		if (ask == null || id == null || match == null || !isOverlord(player)) {
			return;
		}

		Fairness.Refusal why = request(player.level().getServer(), state(match), player, event, id, ask.x(), ask.z());
		Component text = why == null ? Component.translatableWithFallback("cyberblock.overlord.queued", "Queued: it lands after the warning")
			: Component.translatableWithFallback("cyberblock.overlord.refused." + why.key(), why.english(), why.args());
		player.sendOverlayMessage(text);
		poll(player, false, why == null ? "" : why.text());
	}

	/**
	 * One spawn or event request, from a human Overlord ({@code by}) or the Auto-Director ({@code by} null): the entry, its cooldown and
	 * cost, the ground and the fairness rules; on success it is queued, paid and announced. Null on success, else why not.
	 */
	static Fairness.@Nullable Refusal request(final MinecraftServer s, final MatchState m, final @Nullable ServerPlayer by, final boolean event,
		final Identifier id, final double x, final double z) {
		OverlordContent.SpawnEntry spawn = event ? null : OverlordContent.spawns().get(id);
		OverlordContent.EventEntry ev = event ? OverlordContent.events().get(id) : null;
		if (spawn == null && ev == null) {
			return new Fairness.Refusal("unknown", "No such entry: %s", id.toString());
		}

		long now = System.currentTimeMillis();
		String key = (event ? "e:" : "s:") + id;
		int wait = waitS(m, key, now);
		if (wait > 0) {
			return new Fairness.Refusal("cooldown", "Ready again in %s s", wait);
		}

		int cost = event ? ev.cost() : spawn.cost();
		if (!m.points.canAfford(cost)) {
			return new Fairness.Refusal("points", "Not enough Threat Points: %s needed, %s banked", cost, m.points.whole());
		}

		// the level: the one of the match's opted-in player nearest the point
		ServerPlayer anchor = s.getPlayerList().getPlayers().stream().filter(p -> eligible(p, m.id))
			.min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - x, p.getZ() - z))).orElse(null);
		if (anchor == null) {
			return new Fairness.Refusal("no_target", "No opted-in player of this match within %s blocks", (int) Fairness.REACH);
		}

		ServerLevel level = anchor.level();
		if (!level.isLoaded(BlockPos.containing(x, 0, z))) {
			return new Fairness.Refusal("not_loaded", "That spot is not loaded");
		}

		Vec3 at = OverlordWorld.ground(level, x, z);
		if (at == null) {
			return new Fairness.Refusal("no_ground", "No ground there: pick a spot on a street or a building");
		}

		int units = event ? OverlordWorld.eventUnits(ev, m.intensity) : Math.max(spawn.count(), spawn.squadCount());
		int radius = event ? ev.radius() : 0;
		Fairness.Refusal why = Fairness.check(new Fairness.Ask(x, z, units, m.live(), m.intensity, whoNear(level, m.id, x, z), area(s, m.id), radius,
			!event));
		if (why != null) {
			return why;
		}

		m.points.spend(cost);
		int cooldown = event ? ev.cooldownS() : spawn.cooldownS();
		if (cooldown > 0) {
			m.cooldowns.put(key, now + cooldown * 1000L);
		}

		int warning = event ? Math.max(ev.warningS() * 20, Fairness.WARNING_TICKS) : Fairness.WARNING_TICKS;
		String name = event ? ev.name() : spawn.name();
		String byName = by == null ? "Auto-Director" : by.getPlainTextName();
		Pending p = new Pending(event, id, name, level, at, s.getTickCount() + warning, units, radius, by == null ? null : by.getUUID(), byName, cost);
		m.pending.add(p);
		m.line(String.format(Locale.ROOT, "%s: %s at %d %d (lands in %d s)", byName, name, (int) at.x, (int) at.z, warning / 20));
		Cyberblock.LOG.info(String.format(Locale.ROOT, "overlord: %s queued %s %s at %.0f %.0f (match %s, %d points left)", byName, event ? "event" : "spawn",
			id, at.x, at.z, m.id, m.points.whole()));
		OverlordWorld.warn(s, m, p);
		return null;
	}

	/** Every player of the level within WHO_RADIUS of the point (players of other matches and of none count as not eligible). */
	static List<Fairness.Who> whoNear(final ServerLevel level, final String match, final double x, final double z) {
		List<Fairness.Who> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (Math.hypot(p.getX() - x, p.getZ() - z) <= WHO_RADIUS && !p.isSpectator()) {
				out.add(new Fairness.Who(p.getPlainTextName(), p.getX(), p.getZ(), eligible(p, match)));
			}
		}

		return out;
	}

	// ---- the tick ----

	private static void tick(final MinecraftServer s) {
		long tick = s.getTickCount(), now = System.currentTimeMillis();
		for (MatchState m : matches.values()) {
			for (Iterator<Pending> it = m.pending.iterator(); it.hasNext();) {
				Pending p = it.next();
				if (tick >= p.due()) {
					it.remove();
					OverlordWorld.land(s, m, p);
				} else if ((p.due() - tick) % 5 == 0) {
					OverlordWorld.marker(p);
				}
			}
		}

		OverlordWorld.tickStrikes(tick);
		if (tick % 20 != 0) {
			return;
		}

		nightCity.tick(now, uuid -> {
			HostSession h = HostSessions.of(uuid);
			return h == null ? uuid.toString() : h.name();
		});
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			String match = matchOf(p);
			if (match != null) {
				state(match).seen = now;
				remind(p, match);
			}
		}

		for (Iterator<MatchState> it = matches.values().iterator(); it.hasNext();) {
			MatchState m = it.next();
			if (now - m.seen > IDLE_MS || !OverlordHooks.running.test(m.id)) {
				it.remove();
				int removed = OverlordWorld.removeMobs(s, m);
				optIn.values().removeIf(m.id::equals);
				Cyberblock.LOG.info("overlord: match {} ended ({} spawns removed)", m.id, removed);
				continue;
			}

			m.points.regen(1.0, m.intensity);
			OverlordWorld.keep(s, m, now);
			m.director = s.getPlayerList().getPlayers().stream().noneMatch(p -> m.id.equals(matchOf(p)) && isOverlord(p));
			if (m.director && tick % DIRECTOR_TICKS == 0) {
				direct(s, m, now);
			}
		}
	}

	/** Tells a player of a match with an Overlord (or the Auto-Director) once how to opt in. */
	private static void remind(final ServerPlayer p, final String match) {
		if (participant(p, match) && !match.equals(optIn.get(p.getUUID())) && told.add(p.getUUID() + "|" + match)) {
			p.sendSystemMessage(Component.translatableWithFallback("cyberblock.overlord.optin_hint",
				"This match has an Overlord. /cyberblock overlord optin on lets it send enemies and events near you (off until you do)"));
		}
	}

	/** One Auto-Director decision for a match without a human Overlord. */
	static void direct(final MinecraftServer s, final MatchState m, final long now) {
		List<ServerPlayer> targets = s.getPlayerList().getPlayers().stream().filter(p -> eligible(p, m.id)).toList();
		if (targets.isEmpty()) {
			return;
		}

		int live = m.live();
		List<AutoDirector.Spawn> spawns = new ArrayList<>();
		OverlordContent.spawns().forEach((id, e) -> spawns.add(new AutoDirector.Spawn(id.toString(), e.group(), e.rec(), e.cost(),
			Math.max(e.count(), e.squadCount()), waitS(m, "s:" + id, now) == 0)));
		List<AutoDirector.Event> events = new ArrayList<>();
		OverlordContent.events().forEach((id, e) -> events.add(new AutoDirector.Event(id.toString(), e.effect().type(), e.cost(),
			waitS(m, "e:" + id, now) == 0)));
		AutoDirector.Choice choice = AutoDirector.decide(new AutoDirector.Input(m.points.points(), m.intensity, live,
			Math.max(0, Math.min(100, OverlordHooks.heat.getAsInt())), pace(s, m, now), (now - m.lastDirector) / 1000.0, spawns, events,
			random.nextDouble()));
		if (choice == null) {
			return;
		}

		// a point beside a random opted-in player, outside the minimum distance; a few tries around it
		ServerPlayer target = targets.get(random.nextInt(targets.size()));
		double r = m.intensity.minDistance() + 6.0, start = random.nextDouble() * Math.PI * 2.0;
		for (int i = 0; i < 8; i++) {
			double a = start + i * Math.PI / 4.0;
			Fairness.Refusal why = request(s, m, null, choice.event(), Identifier.parse(choice.id()), target.getX() + Math.cos(a) * r,
				target.getZ() + Math.sin(a) * r);
			if (why == null) {
				m.lastDirector = now;
				return;
			}
		}
	}

	// ---- commands ----

	private static void commands(final CommandDispatcher<CommandSourceStack> dispatcher) {
		var intensity = Commands.literal("intensity").executes(c -> status(c.getSource()));
		for (Intensity i : Intensity.values()) {
			intensity = intensity.then(Commands.literal(i.key()).executes(c -> intensity(c.getSource(), i)));
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("overlord")
			.executes(c -> status(c.getSource()))
			.then(Commands.literal("status").executes(c -> status(c.getSource())))
			.then(Commands.literal("console").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				open(p, "command");
				return 1;
			}))
			.then(Commands.literal("optin")
				.then(Commands.literal("on").executes(c -> optIn(c.getSource(), true)))
				.then(Commands.literal("off").executes(c -> optIn(c.getSource(), false))))
			.then(intensity)));
	}

	private static int optIn(final CommandSourceStack source, final boolean on) {
		ServerPlayer p = source.getPlayer();
		String match = p == null ? null : matchOf(p);
		if (p == null || match == null || !participant(p, match)) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.overlord.optin_no_match", "Opting in needs a running match you play in"));
			return 0;
		}

		setOptIn(p, on);
		source.sendSystemMessage(on
			? Component.translatableWithFallback("cyberblock.overlord.optin_on", "Opted in: the Overlord may send enemies and events near you in this match")
			: Component.translatableWithFallback("cyberblock.overlord.optin_off", "Opted out: no Overlord spawns or events near you"));
		return 1;
	}

	/** Opts a player of a running match in or out (for 8M's match screens too). Server thread. */
	public static void setOptIn(final ServerPlayer p, final boolean on) {
		String match = matchOf(p);
		if (on && match != null) {
			optIn.put(p.getUUID(), match);
		} else {
			optIn.remove(p.getUUID());
		}

		Cyberblock.LOG.info("overlord: {} opted {} (match {})", p.getPlainTextName(), on ? "in" : "out", match);
	}

	/** Sets a match's intensity (for 8M's match screens too). Server thread. */
	public static void setIntensity(final String match, final Intensity intensity) {
		MatchState m = state(match);
		m.intensity = intensity;
		m.points.regen(0.0, intensity);
		m.line("Intensity: " + intensity.key());
		Cyberblock.LOG.info("overlord: match {} intensity {}", match, intensity.key());
	}

	/** Host-set: a Cyberpunk-linked player of the match, or an operator (the singleplayer owner too). */
	private static int intensity(final CommandSourceStack source, final Intensity intensity) {
		ServerPlayer p = source.getPlayer();
		String match = p == null ? null : matchOf(p);
		boolean op = Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || p != null && source.getServer().isSingleplayerOwner(p.nameAndId());
		if (match == null || !(op || HostSessions.isHost(p.getUUID()))) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.overlord.intensity_denied",
				"The intensity is set by a Cyberpunk-linked player of a running match (or an operator)"));
			return 0;
		}

		setIntensity(match, intensity);
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.overlord.intensity_set", "Overlord intensity: %s", intensity.key()));
		return 1;
	}

	private static int status(final CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		String match = p == null ? null : matchOf(p);
		if (match == null) {
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.overlord.status_none", "Overlord: you are not in a running match"));
			return 1;
		}

		MatchState m = state(match);
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.overlord.status",
			"Overlord, match %s: %s of %s Threat Points, intensity %s, %s of %s spawns alive, %s, you are %s", match, m.points.whole(),
			m.intensity.maxPoints(), m.intensity.key(), m.live(), m.intensity.cap(), m.director ? "Auto-Director" : "human Overlord",
			match.equals(optIn.get(p.getUUID())) ? "opted in" : "not opted in"));
		return 1;
	}

	static @Nullable MinecraftServer server() {
		return server;
	}
}
