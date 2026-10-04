package dev.cyberblock.mode.heat;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.cyber.Kills;
import dev.cyberblock.hack.breach.Breach;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.holo.HoloLayers;
import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.hub.Leaderboards;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server Heat (megaplan 8-T1): one server-wide meter 0..100 fed by chaos (explosions, kills, destroyed cars, failed breaches, PvP
 * kills) and cooling over time ({@code heat_tier}), with tiers 1-5. For hosts each tier maps to their real wanted level through
 * {@code heat} DOWN (Phase 0 e), so NCPD and Max-Tac are Night City's own; whether a host's game really follows is checked at runtime
 * ({@link WantedCheck}) and one line per host says which path is active. Minecraft-only players (and hosts whose wanted level did not
 * follow) are hunted by Minecraft {@link Squads}. Every player's HUD gets the meter ({@code cyberblock:ui heat}); the Holo-Table gets a
 * {@code heat} layer; chaos counts on the hub's Chaos leaderboard. It replaces 6-B1's interim hook ({@link Breach#heat}).
 *
 * <p>Other phases feed it through {@link #add} (Overlord events, matches) and read {@link #value} and {@link #tier} (the Auto-Director).
 * Server thread.
 */
public final class ServerHeat {
	/** Chaos spots the Holo-Table layer remembers (for this long, server ticks: 5 minutes). */
	static final long SPOT_TICKS = 6000;
	static final int SPOT_CAP = 512;
	private static final HeatMeter meter = new HeatMeter();
	private static final ChaosBoard chaos = new ChaosBoard();
	private static final Squads squads = new Squads();
	private static final Map<UUID, WantedCheck> checks = new HashMap<>();
	private static final Map<UUID, Integer> sentWanted = new HashMap<>();
	private static final Map<UUID, HeatWire.Meter> shown = new HashMap<>();
	private static final Deque<Spot> spots = new ArrayDeque<>();
	private static List<HeatTier> tiers = HeatRules.BUILT_IN;
	private static int lastTier;
	private static String lastSquads = "";
	private static long lastSquadLog;
	private static @Nullable MinecraftServer server;

	/** Where chaos happened, how much and when (for the Holo-Table layer). */
	record Spot(double x, double z, double amount, long tick) {
	}

	private ServerHeat() {
	}

	/** Common init, once (Cyberblock.onInitialize). */
	public static void init() {
		HeatTiers.register();
		HostSessions.listen("killed", ServerHeat::onKilled);
		HostSessions.onStart(s -> {
			sentWanted.remove(s.uuid());
			checks.put(s.uuid(), new WantedCheck());
		});
		HostSessions.onEnd(s -> {
			sentWanted.remove(s.uuid());
			checks.remove(s.uuid());
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(ServerHeat::onDeath);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (squads.stale(entity)) {
				entity.discard();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(ServerHeat::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> shown.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STARTED.register(ServerHeat::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopping());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("heat").executes(c -> status(c.getSource()))
				.then(Commands.literal("set").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.argument("value", IntegerArgumentType.integer(0, 100)).executes(c -> set(c.getSource(), IntegerArgumentType.getInteger(c, "value")))))
				.then(Commands.literal("clear").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> clear(c.getSource()))))));
		// 6-B1's interim hook: a failed breach on a host's device adds to the meter (the start-up line of Breach says "installed")
		Breach.heat = (host, cause, amount) -> add(HeatRules.Source.FAILED_BREACH, cause, amount, cause.position());
		HoloLayers.register("heat", (s, viewer) -> layer(s.getTickCount()));
	}

	/** The meter 0..100. */
	public static double value() {
		return meter.value();
	}

	/** The tier 0..5. */
	public static int tier() {
		return HeatRules.tier(meter.value(), tiers);
	}

	/**
	 * Chaos happened: the meter gains {@code amount} (within the gain budget) and {@code cause}, when a player, is credited with it (the
	 * loudest players are chased; the Chaos leaderboard counts it). {@code at}: where, for the Holo-Table layer (null: unknown).
	 */
	public static void add(final HeatRules.Source source, final @Nullable ServerPlayer cause, final double amount, final @Nullable Vec3 at) {
		MinecraftServer s = server;
		if (s == null || !(amount > 0.0)) {
			return;
		}

		long now = s.getTickCount();
		double gained = meter.feed(amount, now);
		if (cause != null) {
			chaos.add(cause.getUUID(), amount);
			int points = (int) Math.round(amount);
			if (points > 0) {
				Leaderboards.add(cause, Leaderboards.CHAOS, points);
			}
		}

		if (at != null) {
			spots.addLast(new Spot(at.x, at.z, amount, now));
			while (spots.size() > SPOT_CAP) {
				spots.removeFirst();
			}
		}

		Cyberblock.LOG.debug("heat: +{} ({} by {}) -> {}", gained, source, cause == null ? "nobody" : cause.getPlainTextName(), meter.value());
	}

	/** From the explosion mixin: a blast (not a wind burst) in the overworld, sized by its radius (TNT 4 = the base amount). */
	public static void explosion(final ServerLevel level, final Level.ExplosionInteraction interaction, final double x, final double y, final double z,
		final double power, final @Nullable Entity cause) {
		if (interaction == Level.ExplosionInteraction.TRIGGER || level != level.getServer().overworld() || !(power > 0.0)) {
			return;
		}

		double amount = HeatRules.Source.EXPLOSION.amount() * Math.clamp(power / 4.0, 0.25, 2.0);
		add(HeatRules.Source.EXPLOSION, cause instanceof ServerPlayer p ? p : null, amount, new Vec3(x, y, z));
	}

	/** A host's V killed someone in Night City ({@code killed} UP): a car, a hostile or a civilian. */
	private static void onKilled(final HostSession s, final JsonObject m) {
		Kills.Killed k = Kills.parse(m);
		ServerPlayer steve = s.player();
		if (k == null || steve == null) {
			return;
		}

		HeatRules.Source source = k.vehicle() ? HeatRules.Source.CAR_DESTROYED : k.hostile() ? HeatRules.Source.HOSTILE_KILL : HeatRules.Source.CIVILIAN_KILL;
		add(source, steve, source.amount(), steve.position());
	}

	/** Minecraft-side kills by a player: Heat squads, and mobs that are not monsters. Players are counted when they go down (Downed). */
	private static void onDeath(final LivingEntity dead, final DamageSource source) {
		if (dead instanceof ServerPlayer || dead instanceof ProxyEntity) {
			return; // players: Downed counts the PvP "kill" when the victim goes down; figures: their owner's killed message counts
		}

		ServerPlayer killer = killer(dead, source);
		if (killer == null) {
			return;
		}

		if (squads.isUnit(dead)) {
			add(HeatRules.Source.SQUAD_KILL, killer, HeatRules.Source.SQUAD_KILL.amount(), dead.position());
		} else if (!(dead instanceof Enemy)) {
			add(HeatRules.Source.MOB_KILL, killer, HeatRules.Source.MOB_KILL.amount(), dead.position());
		}
	}

	/** The player behind a death: the damage's owner, else the last player that hurt it. */
	public static @Nullable ServerPlayer killer(final LivingEntity dead, final DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer p) {
			return p;
		}

		return dead.getKillCredit() instanceof ServerPlayer p ? p : null;
	}

	private static void started(final MinecraftServer s) {
		server = s;
		meter.set(0.0, s.getTickCount());
		chaos.clear();
		checks.clear();
		sentWanted.clear();
		shown.clear();
		spots.clear();
		lastTier = 0;
		tiers = HeatRules.sorted(HeatTiers.active());
		Cyberblock.LOG.info("heat: ready ({}); hosts: the real wanted level through heat DOWN (Phase 0 e, not confirmed in game yet: checked per "
			+ "host at runtime, fallback Minecraft squads); Minecraft-only players: Minecraft squads", HeatTiers.source());
	}

	private static void stopping() {
		squads.clear();
		server = null;
	}

	private static void tick(final MinecraftServer s) {
		if (s.getTickCount() % 20 != 0) {
			return;
		}

		long now = s.getTickCount();
		tiers = HeatRules.sorted(HeatTiers.active());
		meter.tick(now, tiers);
		chaos.decay(1.0);
		while (!spots.isEmpty() && now - spots.peekFirst().tick() > SPOT_TICKS) {
			spots.removeFirst();
		}

		HeatTier tier = HeatRules.tierOf(meter.value(), tiers);
		int t = tier == null ? 0 : tier.tier();
		if (t != lastTier) {
			Cyberblock.LOG.info("heat: tier {} -> {} (meter {})", lastTier, t, Math.round(meter.value()));
			if (t > lastTier) {
				s.getPlayerList().broadcastSystemMessage(tierMessage(t), true);
			}

			lastTier = t;
		}

		UUID top = chaos.top();
		hosts(tier, top, now);
		hunt(s, tier, top, now);
		ui(s, t, top);
	}

	/** Each active host: its real wanted level for the tier (sent on change), and its runtime check. */
	private static void hosts(final @Nullable HeatTier tier, final @Nullable UUID top, final long now) {
		for (HostSession session : HostSessions.active()) {
			WantedCheck check = checks.computeIfAbsent(session.uuid(), u -> new WantedCheck());
			int wanted = HeatRules.wanted(tier, session.uuid().equals(top));
			Integer sent = sentWanted.get(session.uuid());
			if (sent == null && wanted == 0) {
				sentWanted.put(session.uuid(), 0); // nothing to undo at the start of a session
			} else if (sent == null || sent != wanted) {
				session.send(HeatWire.down(wanted));
				sentWanted.put(session.uuid(), wanted);
				check.sent(wanted, now);
				Cyberblock.LOG.info("heat: {} wanted {} (tier {}{})", session.name(), wanted, tier == null ? 0 : tier.tier(),
					session.uuid().equals(top) ? ", top chaos" : "");
			}

			if (check.update(session.wanted(), now)) {
				if (check.state() == WantedCheck.State.CONFIRMED) {
					Cyberblock.LOG.info("heat: {} real wanted level path active (stats.wanted {} after heat {}): Night City's NCPD and Max-Tac answer "
						+ "for this host", session.name(), session.wanted(), check.expected());
				} else {
					Cyberblock.LOG.info("heat: {} real wanted level not seen within {} s after heat {} (stats.wanted {}): fallback active, Minecraft "
						+ "squads hunt this host", session.name(), WantedCheck.WINDOW_TICKS / 20, check.expected(), session.wanted());
				}
			}
		}
	}

	/** Who squads may hunt now: an online player in the replica, playing (not creative or spectating), not downed, not locked. */
	private static boolean huntable(final ServerPlayer p, final MinecraftServer s) {
		if (p.level() != s.overworld() || p.isSpectator() || p.isCreative() || !p.isAlive() || HoloHooks.downed.test(p) || JoinLock.locked(p)) {
			return false;
		}

		HostSession session = HostSessions.of(p);
		if (session == null || session.state() == HostSession.State.NONE) {
			return true; // a Minecraft-only player
		}

		WantedCheck check = checks.get(p.getUUID());
		return check != null && check.squadsHunt();
	}

	private static void hunt(final MinecraftServer s, final @Nullable HeatTier tier, final @Nullable UUID top, final long now) {
		Set<UUID> eligible = new HashSet<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (huntable(p, s)) {
				eligible.add(p.getUUID());
			}
		}

		List<HeatRules.Ranked> ranked = new ArrayList<>(chaos.ranked(eligible::contains));
		for (UUID u : eligible) {
			if (ranked.stream().noneMatch(r -> r.uuid().equals(u))) {
				ranked.add(new HeatRules.Ranked(u, 0.0));
			}
		}

		squads.reconcile(s, HeatRules.plan(tier, ranked, top), p -> huntable(p, s));
		String described = String.join(", ", squads.describe());
		if (!described.equals(lastSquads) && now - lastSquadLog >= 200) {
			lastSquads = described;
			lastSquadLog = now;
			Cyberblock.LOG.info("heat: Minecraft squads {}", described.isEmpty() ? "none" : described);
		}
	}

	/** The HUD meter to every player whose view of it changed. */
	private static void ui(final MinecraftServer s, final int tier, final @Nullable UUID top) {
		ServerPlayer topPlayer = top == null ? null : s.getPlayerList().getPlayer(top);
		String topName = topPlayer == null ? "" : topPlayer.getPlainTextName();
		int value = (int) Math.round(meter.value());
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			HeatWire.Meter m = new HeatWire.Meter(value, tier, value > 0 ? topName : "", squads.hunting(p.getUUID()));
			if (!m.equals(shown.get(p.getUUID()))) {
				shown.put(p.getUUID(), m);
				ServerNet.ui(p, HeatWire.T, HeatWire.ui(m));
			}
		}
	}

	static Component tierMessage(final int tier) {
		String english = switch (tier) {
			case 1 -> "Server Heat 1: more NCPD patrols";
			case 2 -> "Server Heat 2: NCPD is chasing the loudest players";
			case 3 -> "Server Heat 3: heavy units and drones";
			case 4 -> "Server Heat 4: Max-Tac is hunting the top chaos player";
			default -> "Server Heat 5: full response on everyone";
		};
		return Component.translatableWithFallback("cyberblock.heat.tier." + tier, english);
	}

	/** The Holo-Table's heat layer: recent chaos summed per 64-block square, newer counting more, strongest first. */
	static List<HoloWire.LayerItem> layer(final long now) {
		Map<Long, double[]> squares = new HashMap<>();
		double max = 0.0;
		for (Spot sp : spots) {
			long key = ((long) Math.floorDiv((int) Math.floor(sp.x()), 64) << 32) | (Math.floorDiv((int) Math.floor(sp.z()), 64) & 0xFFFFFFFFL);
			double w = sp.amount() * Math.max(0.0, 1.0 - (double) (now - sp.tick()) / SPOT_TICKS);
			double[] sum = squares.computeIfAbsent(key, k -> new double[1]);
			sum[0] += w;
			max = Math.max(max, sum[0]);
		}

		List<HoloWire.LayerItem> out = new ArrayList<>();
		for (Map.Entry<Long, double[]> e : squares.entrySet()) {
			if (max <= 0.0 || e.getValue()[0] <= 0.0) {
				continue;
			}

			float v = (float) (e.getValue()[0] / max);
			double x = ((int) (e.getKey() >> 32) + 0.5) * 64, z = ((int) e.getKey().longValue() + 0.5) * 64;
			out.add(new HoloWire.LayerItem(x, z, v, v > 0.66F ? 0xE02020 : 0xFF8A00, Long.toString(Math.round(e.getValue()[0]))));
		}

		out.sort((a, b) -> Float.compare(b.v(), a.v()));
		return out.subList(0, Math.min(out.size(), HoloWire.MAX_ITEMS));
	}

	private static int status(final CommandSourceStack source) {
		MinecraftServer s = source.getServer();
		UUID top = chaos.top();
		ServerPlayer topPlayer = top == null ? null : s.getPlayerList().getPlayer(top);
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.heat.status", "Server Heat %s (tier %s); top chaos: %s; Minecraft squads: %s",
			Math.round(meter.value()), tier(), topPlayer == null ? "-" : topPlayer.getPlainTextName(), squads.size()));
		ServerPlayer player = source.getPlayer();
		WantedCheck check = player == null ? null : checks.get(player.getUUID());
		if (check != null) {
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.heat.path." + check.state().name().toLowerCase(java.util.Locale.ROOT),
				switch (check.state()) {
					case UNTESTED -> "Your Night City: the real wanted level is tried at the next Heat tier";
					case PENDING -> "Your Night City: waiting to see the wanted level follow Server Heat";
					case CONFIRMED -> "Your Night City: your real wanted level follows Server Heat";
					case FALLBACK -> "Your Night City did not take the wanted level: Minecraft squads hunt you instead";
				}));
		}

		return 1;
	}

	private static int set(final CommandSourceStack source, final int value) {
		meter.set(value, source.getServer().getTickCount());
		Cyberblock.LOG.info("heat: set to {} by {}", value, source.getTextName());
		return status(source);
	}

	private static int clear(final CommandSourceStack source) {
		meter.set(0.0, source.getServer().getTickCount());
		chaos.clear();
		squads.clear();
		spots.clear();
		Cyberblock.LOG.info("heat: cleared by {}", source.getTextName());
		return status(source);
	}
}
