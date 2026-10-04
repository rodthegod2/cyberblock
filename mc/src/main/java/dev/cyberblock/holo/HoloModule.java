package dev.cyberblock.holo;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.skin.CyberSkins;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table on the server (megaplan 7-T1): who may look at the table, the table each viewer may see ({@link HoloWire} state:
 * pins filtered by {@link HoloRules#pinVisible}, spawn points with their refusal, layers, replica detail around the players), the
 * spawns themselves and the Spawn Beacons. Server thread only.
 *
 * <p><b>Looking.</b> A right-click on a Holo-Table grants that player the table while it stays within {@link #TABLE_REACH} blocks
 * of it (operators and the singleplayer owner also get it anywhere for 10 minutes with {@code /cyberblock holo}); the open view asks
 * for the table once a second ({@code act holo}) and gets it back ({@code ui holo}). Nobody else gets pins: a request without a
 * grant is dropped.
 *
 * <p><b>Spawning.</b> A Minecraft-only player (or a host whose Cyberpunk is not active) is placed in the replica: on the floor under
 * the spot (refused when Night City has no ground there yet). A host whose Cyberpunk is active moves V: {@code vteleport
 * {id, p, yaw, why:"holo"}} DOWN (the scripts' settle probe and ground snap, Phase 0 (g)); on {@code vteleported ok} Steve is put on
 * V's snapped spot (within 32 blocks of the asked one), so V and Steve stay paired; a failure says why and the cooldown is given
 * back. A host at the table in the hub first steps out to the spot (7H's leave unparks V) and the vteleport follows 1.5 s later.
 * Then 5 s of spawn protection and a 60 s cooldown.
 *
 * <p><b>Fast travel.</b> A host's own Cyberpunk fast-travel point (7V's list) goes through the server too ({@code act holotravel},
 * {@link #onTravel}): the same rules, the same hub exit, then {@code ui holotravel} tells the client to run {@code uiact fasttravel}.
 *
 * <p><b>Privacy and load.</b> Spawn Beacons follow their owner's privacy ({@link HoloRules#beaconOffered}); spawn, hide and fast
 * travel share a per-player rate limit (2 a second, a burst of 4; more are dropped quietly); a request never loads a chunk before
 * its cooldown check.
 *
 * <p>Log lines: {@code holo: <name> opened the table (<where>)}, {@code holo: <name> spawned at <kind> <id> (<x> <y> <z>)},
 * {@code holo: <name> -> <kind> <id>: vteleport <id> sent}, {@code holo: <name> -> <kind> <id> ok (<x> <y> <z>)},
 * {@code holo: <name> -> <kind> <id> failed (<reason>)}, {@code holo: <name> refused <kind> <id> (<lang key>)},
 * {@code holo: <name> pin hidden|shown}, {@code holo: beacon placed|removed at <x> <y> <z> by <name>},
 * {@code holo: <name> -> travel <point>: leaving the hub first}, {@code holo: <name> -> travel <point>: go}.
 */
public final class HoloModule {
	static final double TABLE_REACH = 8.0;
	static final long COMMAND_GRANT_MS = 10 * 60_000L;
	static final long POLL_MIN_MS = 400L;
	static final int DETAIL_RADIUS = 16;
	static final int DETAIL_TICKS = 100;
	static final int HUB_LEAVE_TICKS = 30;
	static final long ANSWER_MS = 30_000L;
	static final int CLIMB = 64, DROP = 48;

	/** Hide my pin (megaplan 1.13: a persisted per-player attachment); kept through death. */
	public static final AttachmentType<Boolean> HIDE_PIN = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.initializer(() -> false)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "hide_pin"));

	/** Who may look: at a table (pos) in a dimension, or anywhere (table null) until {@code at + COMMAND_GRANT_MS}. */
	private record Grant(ResourceKey<Level> dim, @Nullable BlockPos table, long at) {
	}

	/** A resolved spawn point (never the client's coordinates). */
	record Target(HoloRules.Kind kind, String id, String name, Vec3 pos, float yaw, boolean teamSpawn, String district, String owner,
		@Nullable UUID friend) {
	}

	/** A V teleport waiting for its vteleported, keyed by the host; sendTick > 0 while it waits to be sent (hub exit). */
	private record Pending(int id, Target target, double[] p, long at, int sendTick) {
	}

	private record Cached(int tick, HoloWire.Detail detail) {
	}

	/** A fast travel waiting for the hub exit (sendTick) before the client is told to go. */
	private record TravelWait(HoloWire.Travel travel, int sendTick) {
	}

	private static final Map<UUID, double[]> buckets = new HashMap<>();
	private static final Map<UUID, TravelWait> travels = new HashMap<>();
	private static long dropped;
	private static final Map<UUID, Grant> grants = new HashMap<>();
	private static final Map<UUID, Long> polled = new HashMap<>();
	private static final Map<UUID, Long> lastSpawn = new HashMap<>();
	private static final Map<UUID, Long> protectedSince = new HashMap<>();
	private static final Map<UUID, Pending> pending = new HashMap<>();
	private static final Map<UUID, Cached> details = new HashMap<>();

	private HoloModule() {
	}

	/** Common init, once: the blocks, the landmark kind, the layers, the acts, the vteleported answer, protection and commands. */
	public static void init() {
		HoloBlocks.register();
		Landmarks.register();
		HoloLayers.registerBuiltIn();
		ServerNet.onAct(HoloWire.T_POLL, HoloModule::onPoll);
		ServerNet.onAct(HoloWire.T_SPAWN, HoloModule::onSpawn);
		ServerNet.onAct(HoloWire.T_TRAVEL, HoloModule::onTravel);
		ServerNet.onAct(HoloWire.T_HIDE, (player, m) -> {
			if (!allowed(player)) {
				return;
			}

			Boolean on = HoloWire.parseHide(m);
			if (on != null && granted(player)) {
				hide(player, on);
				send(player, false, false);
			}
		});
		HostSessions.listen("vteleported", HoloModule::onAnswer);
		HostSessions.onEnd(s -> {
			boolean spawning = pending.remove(s.uuid()) != null;
			boolean travelling = travels.remove(s.uuid()) != null;
			if (spawning || travelling) {
				lastSpawn.remove(s.uuid());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(HoloModule::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayer p) || !HoloRules.protectedNow(protectedSince.get(p.getUUID()), System.currentTimeMillis()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			grants.clear();
			polled.clear();
			lastSpawn.clear();
			protectedSince.clear();
			pending.clear();
			details.clear();
			buckets.clear();
			travels.clear();
			dropped = 0;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
		// Phase 0 (g) is not confirmed in game yet: say which path a linked spawn takes
		Cyberblock.LOG.info("holo: Holo-Table ready ({} layers, beacons up to {} per player); linked spawns use vteleport why:holo with the scripts' "
			+ "settle probe and ground snap (Phase 0 g unconfirmed in game: a refused or failed teleport leaves V where V is and says why), "
			+ "Minecraft-only spawns stand Steve on the replica's floor", HoloLayers.IDS.size(), Beacons.PER_PLAYER);
	}

	private static void forget(final UUID uuid) {
		grants.remove(uuid);
		polled.remove(uuid);
		protectedSince.remove(uuid);
		details.remove(uuid);
		buckets.remove(uuid);
		travels.remove(uuid);
	}

	/** The shared spawn, hide and fast-travel rate limit; a request over it is dropped quietly (counted at debug level). */
	private static boolean allowed(final ServerPlayer player) {
		long now = System.currentTimeMillis();
		if (HoloRules.take(buckets.computeIfAbsent(player.getUUID(), u -> HoloRules.bucket(now)), now)) {
			return true;
		}

		dropped++;
		Cyberblock.LOG.debug("holo: a request of {} over the rate limit dropped ({} so far)", player.getPlainTextName(), dropped);
		return false;
	}

	// ---- looking ----

	/** A right-click on a Holo-Table: the player gets the table there, and its first state opens the view. */
	static void open(final ServerPlayer player, final BlockPos table) {
		if (JoinLock.locked(player)) {
			return;
		}

		grants.put(player.getUUID(), new Grant(player.level().dimension(), table.immutable(), System.currentTimeMillis()));
		Cyberblock.LOG.info("holo: {} opened the table ({} {} {})", player.getPlainTextName(), table.getX(), table.getY(), table.getZ());
		send(player, true, true);
	}

	/** May this player look at the table now? */
	static boolean granted(final ServerPlayer player) {
		Grant g = grants.get(player.getUUID());
		if (g == null) {
			return false;
		}

		if (g.table() == null) {
			return System.currentTimeMillis() - g.at() < COMMAND_GRANT_MS;
		}

		return player.level().dimension() == g.dim() && player.position().distanceToSqr(Vec3.atCenterOf(g.table())) <= TABLE_REACH * TABLE_REACH;
	}

	private static void onPoll(final ServerPlayer player, final JsonObject m) {
		long now = System.currentTimeMillis();
		Long last = polled.get(player.getUUID());
		if (!granted(player) || last != null && now - last < POLL_MIN_MS) {
			return;
		}

		polled.put(player.getUUID(), now);
		send(player, false, HoloWire.pollDetail(m));
	}

	private static void send(final ServerPlayer player, final boolean open, final boolean detail) {
		MinecraftServer server = player.level().getServer();
		ServerNet.ui(player, HoloWire.T_STATE, HoloWire.state(state(server, player, open, detail)));
	}

	static boolean hidden(final ServerPlayer player) {
		return Boolean.TRUE.equals(player.getAttachedOrElse(HIDE_PIN, false));
	}

	static HoloRules.Who who(final ServerPlayer player) {
		HoloHooks.Match m = HoloHooks.match.apply(player);
		return new HoloRules.Who(player.getUUID(), m.id(), m.team(), m.role(), hidden(player));
	}

	/** The players in Night City (the overworld) whose pins this viewer may see. */
	static List<ServerPlayer> visible(final MinecraftServer server, final ServerPlayer viewer) {
		HoloRules.Who v = who(viewer);
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level() == server.overworld() && !JoinLock.locked(p) && HoloRules.pinVisible(v, who(p), HoloHooks.revealed.test(viewer, p))) {
				out.add(p);
			}
		}

		return out;
	}

	static HoloWire.State state(final MinecraftServer server, final ServerPlayer viewer, final boolean open, final boolean withDetail) {
		HoloRules.Who v = who(viewer);
		List<ServerPlayer> visible = visible(server, viewer);
		List<HoloWire.Pin> pins = new ArrayList<>();
		for (ServerPlayer p : visible) {
			HostSession s = HostSessions.of(p);
			boolean drives = s != null && s.active();
			int team = HoloHooks.match.apply(p).team();
			pins.add(new HoloWire.Pin(p.getUUID(), p.getPlainTextName(), p.getX(), p.getY(), p.getZ(), p.getYRot(), CyberSkins.linked(p), team,
				HoloHooks.teamColour.applyAsInt(p), drives ? s.vehicle() : p.isPassenger(), inCombat(p), HoloHooks.downed.test(p), p == viewer));
		}

		List<HoloWire.Layer> layers;
		try {
			layers = HoloLayers.all(server, viewer);
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("holo: a layer failed: {}", e.toString());
			layers = List.of();
		}

		List<HoloWire.Detail> detail = null;
		if (withDetail) {
			detail = new ArrayList<>();
			for (ServerPlayer p : visible) {
				if (detail.size() < HoloWire.MAX_DETAIL) {
					detail.add(detail(server, p));
				}
			}
		}

		return new HoloWire.State(open, v.inMatch(), v.role().key(), v.hidden(), (int) HoloRules.cooldownLeft(lastSpawn.get(viewer.getUUID()),
			System.currentTimeMillis()), pins, spawnPoints(server, viewer, v, visible), layers, detail);
	}

	/** In combat: hurt by, or hurting, something within the last 10 s. */
	static boolean inCombat(final ServerPlayer p) {
		int since = Math.min(stamp(p, p.getLastHurtByMobTimestamp()), stamp(p, p.getLastHurtMobTimestamp()));
		return since < 200;
	}

	private static int stamp(final ServerPlayer p, final int at) {
		return at <= 0 ? Integer.MAX_VALUE : p.tickCount - at;
	}

	// ---- spawn points ----

	/** Every spawn point this viewer is offered: landmarks, Spawn Beacons, its team bases, the friends it can see. */
	static List<HoloWire.SpawnPoint> spawnPoints(final MinecraftServer server, final ServerPlayer viewer, final HoloRules.Who v, final List<ServerPlayer> visible) {
		List<Target> targets = new ArrayList<>();
		for (Map.Entry<Identifier, Landmarks.Landmark> e : Landmarks.all()) {
			Landmarks.Landmark l = e.getValue();
			if (l.spawn()) {
				targets.add(new Target(HoloRules.Kind.LANDMARK, e.getKey().toString(), l.name(), l.pos(), l.yaw(), false, l.district(), "", null));
			}
		}

		ServerLevel city = server.overworld();
		Beacons beacons = beacons(server);
		for (Beacons.Beacon b : beacons.all()) {
			if (city.isLoaded(b.pos()) && !city.getBlockState(b.pos()).is(HoloBlocks.SPAWN_BEACON)) {
				beacons.remove(b.pos()); // broken some other way (a piston, a command): no longer a spawn point
				continue;
			}

			if (HoloRules.beaconOffered(v, b.owner(), b.hidden(), b.matchOrNull(), b.team())) {
				targets.add(beaconTarget(v, b));
			}
		}

		for (HoloHooks.Base base : HoloHooks.bases.apply(viewer)) {
			if (HoloRules.teamSpawn(v, base.match(), base.team())) {
				targets.add(new Target(HoloRules.Kind.BASE, base.id(), base.name(), base.pos(), 0.0F, true, "", "", null));
			}
		}

		for (ServerPlayer p : visible) {
			if (p != viewer) {
				targets.add(friend(p));
			}
		}

		long cooldown = HoloRules.cooldownLeft(lastSpawn.get(viewer.getUUID()), System.currentTimeMillis());
		boolean busy = busy(viewer);
		List<HoloWire.SpawnPoint> out = new ArrayList<>();
		for (Target t : targets) {
			String why = HoloRules.refusal(v, ask(server, viewer, t), cooldown, busy);
			out.add(new HoloWire.SpawnPoint(t.kind().key(), t.id(), t.name(), t.pos().x, t.pos().y, t.pos().z, t.district(), t.owner(), why == null,
				why == null ? "" : why));
		}

		return out;
	}

	private static Target beaconTarget(final HoloRules.Who v, final Beacons.Beacon b) {
		return new Target(HoloRules.Kind.BEACON, b.id(), b.ownerName(), Vec3.atBottomCenterOf(b.pos().above()), 0.0F,
			HoloRules.teamSpawn(v, b.matchOrNull(), b.team()), "", b.ownerName(), null);
	}

	private static Target friend(final ServerPlayer p) {
		return new Target(HoloRules.Kind.FRIEND, p.getUUID().toString(), p.getPlainTextName(), p.position(), p.getYRot(), false, "", "", p.getUUID());
	}

	/** The rule inputs of one spawn for this viewer. */
	private static HoloRules.Ask ask(final MinecraftServer server, final ServerPlayer viewer, final Target t) {
		HostSession s = HostSessions.of(viewer);
		boolean drives = s != null && s.active();
		boolean self = viewer.getUUID().equals(t.friend());
		ServerPlayer friend = t.friend() == null ? null : server.getPlayerList().getPlayer(t.friend());
		boolean friendVisible = friend != null && HoloRules.pinVisible(who(viewer), who(friend), HoloHooks.revealed.test(viewer, friend));
		boolean friendInCity = friend != null && friend.level() == server.overworld() && !JoinLock.locked(friend);
		return new HoloRules.Ask(t.kind(), t.teamSpawn(), HoloHooks.lockedZone.locked(server.overworld(), t.pos(), viewer), self, friendVisible, friendInCity,
			drives && s.held(), drives && s.vehicle());
	}

	/** The spawn point a request names, looked up on the server (null when there is none for this viewer). */
	static @Nullable Target resolve(final MinecraftServer server, final ServerPlayer viewer, final HoloWire.SpawnAsk a) {
		HoloRules.Who v = who(viewer);
		return switch (a.kind()) {
			case LANDMARK -> {
				Identifier id = Identifier.tryParse(a.id());
				Target found = null;
				for (Map.Entry<Identifier, Landmarks.Landmark> e : Landmarks.all()) {
					Landmarks.Landmark l = e.getValue();
					if (e.getKey().equals(id) && l.spawn()) {
						found = new Target(HoloRules.Kind.LANDMARK, a.id(), l.name(), l.pos(), l.yaw(), false, l.district(), "", null);
					}
				}

				yield found;
			}

			case BEACON -> {
				Beacons beacons = beacons(server);
				Beacons.Beacon b = beacons.byId(a.id());
				if (b == null || !HoloRules.beaconOffered(v, b.owner(), b.hidden(), b.matchOrNull(), b.team())) {
					yield null;
				}

				// only a loaded chunk is checked (a request never loads one); an unloaded beacon counts, as in the spawn list
				ServerLevel city = server.overworld();
				if (city.isLoaded(b.pos()) && !city.getBlockState(b.pos()).is(HoloBlocks.SPAWN_BEACON)) {
					beacons.remove(b.pos());
					yield null;
				}

				yield beaconTarget(v, b);
			}

			case BASE -> HoloHooks.bases.apply(viewer).stream().filter(b -> b.id().equals(a.id()) && HoloRules.teamSpawn(v, b.match(), b.team()))
				.map(b -> new Target(HoloRules.Kind.BASE, b.id(), b.name(), b.pos(), 0.0F, true, "", "", null)).findFirst().orElse(null);
			case FRIEND -> {
				UUID uuid;
				try {
					uuid = UUID.fromString(a.id());
				} catch (IllegalArgumentException e) {
					yield null;
				}

				ServerPlayer p = server.getPlayerList().getPlayer(uuid);
				yield p == null ? null : friend(p);
			}
		};
	}

	// ---- spawning ----

	private static boolean busy(final ServerPlayer player) {
		return pending.containsKey(player.getUUID()) || travels.containsKey(player.getUUID());
	}

	private static void onSpawn(final ServerPlayer player, final JsonObject m) {
		if (!allowed(player)) {
			return;
		}

		HoloWire.SpawnAsk a = HoloWire.parseSpawn(m);
		if (a == null || !granted(player)) {
			return;
		}

		// busy and cooldown first: they need no lookup (and a request in cooldown touches no chunk)
		long now = System.currentTimeMillis();
		long cooldown = HoloRules.cooldownLeft(lastSpawn.get(player.getUUID()), now);
		if (busy(player) || cooldown > 0) {
			refuse(player, a.kind().key(), a.id(), busy(player) ? "cyberblock.holo.pending" : "cyberblock.holo.cooldown");
			return;
		}

		MinecraftServer server = player.level().getServer();
		Target t = resolve(server, player, a);
		if (t == null) {
			refuse(player, a.kind().key(), a.id(), "cyberblock.holo.gone");
			return;
		}

		String why = HoloRules.refusal(who(player), ask(server, player, t), cooldown, false);
		if (why != null) {
			refuse(player, t.kind().key(), t.id(), why);
			return;
		}

		HostSession s = HostSessions.of(player);
		if (s != null && s.active()) {
			moveV(server, s, player, t, now);
		} else {
			placeSteve(server, player, t, now);
		}
	}

	private static void refuse(final ServerPlayer player, final String kind, final String id, final String key) {
		player.sendSystemMessage(HoloText.refusal(key, HoloRules.cooldownLeft(lastSpawn.get(player.getUUID()), System.currentTimeMillis())));
		// the id may come from the client: no control characters in the log
		Cyberblock.LOG.info("holo: {} refused {} {} ({})", player.getPlainTextName(), kind, id.replaceAll("\\p{Cntrl}", "?"), key);
		if (granted(player)) {
			send(player, false, false);
		}
	}

	/** A Minecraft-only player (or a host whose Cyberpunk is off) is stood on the replica's floor under the spot. */
	private static void placeSteve(final MinecraftServer server, final ServerPlayer player, final Target t, final long now) {
		ServerLevel city = server.overworld();
		Vec3 spot = ground(city, t.pos());
		if (spot == null) {
			refuse(player, t.kind().key(), t.id(), "cyberblock.holo.no_ground");
			return;
		}

		player.teleportTo(city, spot.x, spot.y, spot.z, Set.of(), t.kind() == HoloRules.Kind.FRIEND ? player.getYRot() : t.yaw(), player.getXRot(), true);
		lastSpawn.put(player.getUUID(), now);
		protectedSince.put(player.getUUID(), now);
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.spawned", "Spawned at %s (5 s of protection)", t.name()));
		Cyberblock.LOG.info(String.format(Locale.ROOT, "holo: %s spawned at %s %s (%.1f %.1f %.1f)", player.getPlainTextName(), t.kind().key(), t.id(), spot.x,
			spot.y, spot.z));
	}

	/** A host whose Cyberpunk is active: V moves (vteleport why:"holo") and Steve follows the answer. */
	private static void moveV(final MinecraftServer server, final HostSession s, final ServerPlayer player, final Target t, final long now) {
		double[] p = {t.pos().x, t.pos().y, t.pos().z};
		int id = s.newRequest(player.getUUID());
		boolean away = player.level() != server.overworld();
		if (away) {
			// at the table in the hub: Steve steps out to the spot first (7H's leave unparks V), the vteleport follows
			Vec3 spot = ground(server.overworld(), t.pos());
			Vec3 at = spot == null ? t.pos() : spot;
			player.teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), player.getYRot(), player.getXRot(), true);
		}

		pending.put(player.getUUID(), new Pending(id, t, p, now, away ? server.getTickCount() + HUB_LEAVE_TICKS : -1));
		lastSpawn.put(player.getUUID(), now);
		if (!away) {
			sendTeleport(s, player, pending.get(player.getUUID()));
		}

		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.moving", "Moving V to %s...", t.name()));
	}

	// ---- fast travel ----

	/**
	 * {@code act holotravel}: a host's own Cyberpunk fast travel, picked at the table. The spawn rules apply (cooldown, busy, match,
	 * locked zone at x/z, V held or in a vehicle; never a team spawn); a host at the table in the hub steps out first (7H's leave
	 * pair releases V) and the client is told to go {@link #HUB_LEAVE_TICKS} later, as a V teleport would be; elsewhere at once.
	 * The client then sends {@code uiact fasttravel}, V travels and Steve follows V as always. The cooldown starts on acceptance.
	 */
	private static void onTravel(final ServerPlayer player, final JsonObject m) {
		if (!allowed(player)) {
			return;
		}

		HoloWire.Travel travel = HoloWire.parseTravel(m);
		if (travel == null || !granted(player)) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		ServerLevel city = server.overworld();
		HostSession s = HostSessions.of(player);
		boolean drives = s != null && s.active();
		long now = System.currentTimeMillis();
		Vec3 spot = surface(city, travel.x(), travel.z());
		Vec3 zoneAt = spot != null ? spot : new Vec3(travel.x(), city.getSeaLevel(), travel.z());
		String why = HoloRules.travelRefusal(who(player), drives, HoloHooks.lockedZone.locked(city, zoneAt, player), drives && s.held(),
			drives && s.vehicle(), HoloRules.cooldownLeft(lastSpawn.get(player.getUUID()), now), busy(player));
		if (why != null) {
			refuse(player, "travel", travel.name(), why);
			return;
		}

		lastSpawn.put(player.getUUID(), now);
		if (player.level() == city) {
			travelGo(player, travel);
			return;
		}

		// at the table in the hub: Steve steps out (to the point when Night City is loaded there, else the world spawn), 7H's leave pair
		// (park off, hub off) releases V, then the client is told to go
		Vec3 at = spot;
		if (at == null) {
			BlockPos p = server.getRespawnData().pos();
			at = Vec3.atBottomCenterOf(p);
		}

		player.teleportTo(city, at.x, at.y, at.z, Set.of(), player.getYRot(), player.getXRot(), true);
		travels.put(player.getUUID(), new TravelWait(travel, server.getTickCount() + HUB_LEAVE_TICKS));
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.travel.leaving", "Leaving the Afterlife for %s...", travel.name()));
		Cyberblock.LOG.info("holo: {} -> travel {}: leaving the hub first", player.getPlainTextName(), travel.name().replaceAll("\\p{Cntrl}", "?"));
	}

	private static void travelGo(final ServerPlayer player, final HoloWire.Travel travel) {
		ServerNet.ui(player, HoloWire.T_TRAVEL, HoloWire.travel(travel.name(), travel.x(), travel.z()));
		Cyberblock.LOG.info("holo: {} -> travel {}: go", player.getPlainTextName(), travel.name().replaceAll("\\p{Cntrl}", "?"));
	}

	/** Where to stand at (x, z) in a loaded chunk of Night City (never loads one); null when unloaded or nothing is built there. */
	static @Nullable Vec3 surface(final ServerLevel city, final double x, final double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		LevelChunk chunk = city.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
		if (chunk == null) {
			return null;
		}

		int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
		return top < city.getMinY() ? null : ground(city, new Vec3(x, top + 1, z));
	}

	private static void sendTeleport(final HostSession s, final ServerPlayer player, final Pending p) {
		s.send(HoloWire.vteleport(p.id(), p.p()[0], p.p()[1], p.p()[2], p.target().yaw()));
		Cyberblock.LOG.info("holo: {} -> {} {}: vteleport {} sent", player.getPlainTextName(), p.target().kind().key(), p.target().id(), p.id());
	}

	/** vteleported from a host's DLL: Steve goes to V's snapped spot, or the reason shows and the cooldown is given back. */
	private static void onAnswer(final HostSession s, final JsonObject m) {
		HoloWire.Answer a = HoloWire.parseAnswer(m);
		Pending p = pending.get(s.uuid());
		if (a == null || p == null || p.id() != a.id()) {
			return; // Meet Up's, or not ours
		}

		pending.remove(s.uuid());
		ServerPlayer player = s.player();
		if (player == null) {
			return;
		}

		Target t = p.target();
		if (!a.ok()) {
			lastSpawn.remove(s.uuid());
			player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.failed", "Holo-Table spawn failed: %s", HoloText.reason(a.reason())));
			Cyberblock.LOG.info("holo: {} -> {} {} failed ({})", s.name(), t.kind().key(), t.id(), a.reason());
			return;
		}

		MinecraftServer server = player.level().getServer();
		double[] at = HoloRules.landing(a.p(), p.p());
		player.teleportTo(server.overworld(), at[0], at[1], at[2], Set.of(), player.getYRot(), player.getXRot(), true);
		protectedSince.put(s.uuid(), System.currentTimeMillis());
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.spawned", "Spawned at %s (5 s of protection)", t.name()));
		Cyberblock.LOG.info(String.format(Locale.ROOT, "holo: %s -> %s %s ok (%.1f %.1f %.1f)", s.name(), t.kind().key(), t.id(), at[0], at[1], at[2]));
	}

	/**
	 * Where a player can stand at {@code p}: up out of solid blocks (at most 64), then down to the first floor (at most 48 below);
	 * null when there is no floor (Night City is not built there yet) or the column leaves the world.
	 */
	static @Nullable Vec3 ground(final ServerLevel level, final Vec3 p) {
		BlockPos at = BlockPos.containing(p);
		int up = 0;
		while (up <= CLIMB && !(free(level, at.above(up)) && free(level, at.above(up + 1)))) {
			up++;
		}

		if (up > CLIMB) {
			return null;
		}

		BlockPos feet = at.above(up);
		for (int d = 0; d <= DROP; d++) {
			BlockPos below = feet.below(d + 1);
			if (below.getY() < level.getMinY()) {
				return null;
			}

			if (!free(level, below)) {
				return new Vec3(p.x, feet.below(d).getY(), p.z);
			}
		}

		return null;
	}

	private static boolean free(final ServerLevel level, final BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static void tick(final MinecraftServer server) {
		int now = server.getTickCount();
		for (Map.Entry<UUID, Pending> e : List.copyOf(pending.entrySet())) {
			Pending p = e.getValue();
			if (p.sendTick() > 0 && now >= p.sendTick()) {
				HostSession s = HostSessions.of(e.getKey());
				ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
				if (s == null || !s.active() || player == null) {
					pending.remove(e.getKey());
					lastSpawn.remove(e.getKey());
					continue;
				}

				Pending sent = new Pending(p.id(), p.target(), p.p(), p.at(), -1);
				pending.put(e.getKey(), sent);
				sendTeleport(s, player, sent);
			}
		}

		for (Map.Entry<UUID, TravelWait> e : List.copyOf(travels.entrySet())) {
			if (now < e.getValue().sendTick()) {
				continue;
			}

			travels.remove(e.getKey());
			HostSession s = HostSessions.of(e.getKey());
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (s == null || !s.active() || player == null) {
				lastSpawn.remove(e.getKey());
				continue;
			}

			travelGo(player, e.getValue().travel());
		}

		if (now % 20 != 0) {
			return;
		}

		long ms = System.currentTimeMillis();
		for (Map.Entry<UUID, Pending> e : List.copyOf(pending.entrySet())) {
			if (ms - e.getValue().at() >= ANSWER_MS) {
				pending.remove(e.getKey());
				lastSpawn.remove(e.getKey());
				ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
				if (player != null) {
					player.sendSystemMessage(Component.translatableWithFallback("cyberblock.holo.no_answer", "Holo-Table spawn: no answer from your Cyberpunk game"));
				}

				Cyberblock.LOG.info("holo: {} -> {} {} failed (no answer)", player == null ? e.getKey() : player.getPlainTextName(),
					e.getValue().target().kind().key(), e.getValue().target().id());
			}
		}

		protectedSince.values().removeIf(at -> !HoloRules.protectedNow(at, ms));
		lastSpawn.values().removeIf(at -> HoloRules.cooldownLeft(at, ms) == 0);
		grants.entrySet().removeIf(g -> g.getValue().table() == null && ms - g.getValue().at() >= COMMAND_GRANT_MS);
	}

	// ---- detail ----

	/** The replica around a player: 33 x 33 block columns (surface y and map colour), from loaded chunks only; cached 5 s. */
	static HoloWire.Detail detail(final MinecraftServer server, final ServerPlayer player) {
		int now = server.getTickCount();
		Cached c = details.get(player.getUUID());
		int cx = player.getBlockX(), cz = player.getBlockZ();
		if (c != null && now - c.tick() < DETAIL_TICKS && c.detail().covers(cx, cz)
			&& Math.abs(c.detail().x0() + DETAIL_RADIUS - cx) <= 4 && Math.abs(c.detail().z0() + DETAIL_RADIUS - cz) <= 4) {
			return c.detail();
		}

		ServerLevel level = server.overworld();
		int n = 2 * DETAIL_RADIUS + 1, x0 = cx - DETAIL_RADIUS, z0 = cz - DETAIL_RADIUS;
		short[] heights = new short[n * n];
		byte[] colours = new byte[n * n];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int bx = x0 + x, bz = z0 + z, i = z * n + x;
				heights[i] = HoloWire.UNKNOWN;
				LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
				if (chunk == null) {
					continue;
				}

				int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
				if (top < level.getMinY() || top > Short.MAX_VALUE - 1) {
					continue;
				}

				pos.set(bx, top, bz);
				MapColor colour = chunk.getBlockState(pos).getMapColor(level, pos);
				if (colour == MapColor.NONE) {
					continue; // an outline barrier or nothing to see: the shared map shows there
				}

				heights[i] = (short) (top + 1);
				colours[i] = (byte) colour.id;
			}
		}

		HoloWire.Detail d = new HoloWire.Detail(x0, z0, n, heights, colours);
		details.put(player.getUUID(), new Cached(now, d));
		return d;
	}

	// ---- pin hiding ----

	static void hide(final ServerPlayer player, final boolean on) {
		player.setAttached(HIDE_PIN, on);
		beacons(player.level().getServer()).setHidden(player.getUUID(), on); // its beacons say where it goes too
		player.sendSystemMessage(on
			? Component.translatableWithFallback("cyberblock.holo.hide.on", "Your Holo-Table pin is hidden (outside matches)")
			: Component.translatableWithFallback("cyberblock.holo.hide.off", "Your Holo-Table pin is shown"));
		Cyberblock.LOG.info("holo: {} pin {}", player.getPlainTextName(), on ? "hidden" : "shown");
	}

	// ---- Spawn Beacons ----

	static Beacons beacons(final MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(Beacons.TYPE);
	}

	/** Night City only, and at most PER_PLAYER per player (said on the action bar when refused). */
	static boolean mayPlaceBeacon(final ServerLevel level, final ServerPlayer player) {
		if (level != level.getServer().overworld()) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.holo.beacon.city_only", "Spawn Beacons work only in Night City"));
			return false;
		}

		if (beacons(level.getServer()).count(player.getUUID()) >= Beacons.PER_PLAYER) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.holo.beacon.limit", "You have %s Spawn Beacons already (break one first)",
				Beacons.PER_PLAYER));
			return false;
		}

		return true;
	}

	static void beaconPlaced(final ServerLevel level, final BlockPos pos, final ServerPlayer player) {
		HoloHooks.Match m = HoloHooks.match.apply(player);
		Beacons beacons = beacons(level.getServer());
		if (beacons.add(new Beacons.Beacon(pos.immutable(), player.getUUID(), player.getPlainTextName(), m.id() == null ? "" : m.id(), m.team(),
			hidden(player)))) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.holo.beacon.placed", "Spawn Beacon placed (%s of %s)",
				beacons.count(player.getUUID()), Beacons.PER_PLAYER));
			Cyberblock.LOG.info("holo: beacon placed at {} {} {} by {}", pos.getX(), pos.getY(), pos.getZ(), player.getPlainTextName());
		}
	}

	static void beaconRemoved(final ServerLevel level, final BlockPos pos, final net.minecraft.world.entity.player.Player player) {
		if (beacons(level.getServer()).remove(pos)) {
			Cyberblock.LOG.info("holo: beacon removed at {} {} {} by {}", pos.getX(), pos.getY(), pos.getZ(), player.getPlainTextName());
		}
	}

	static void beaconInfo(final ServerLevel level, final BlockPos pos, final ServerPlayer player) {
		Beacons.Beacon b = beacons(level.getServer()).at(pos);
		player.sendOverlayMessage(b == null
			? Component.translatableWithFallback("cyberblock.holo.beacon.unowned", "A Spawn Beacon (not on the Holo-Table)")
			: Component.translatableWithFallback("cyberblock.holo.beacon.info", "%s's Spawn Beacon: a spawn point on the Holo-Table", b.ownerName()));
	}

	// ---- commands ----

	private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("holo")
			.executes(c -> openHere(c.getSource()))
			.then(Commands.literal("hide")
				.executes(c -> hideCommand(c.getSource(), null))
				.then(Commands.literal("on").executes(c -> hideCommand(c.getSource(), true)))
				.then(Commands.literal("off").executes(c -> hideCommand(c.getSource(), false))))));
	}

	/** /cyberblock holo: operators and the singleplayer owner open the table where they are (10 minutes). */
	private static int openHere(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null || !(Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || source.getServer().isSingleplayerOwner(player.nameAndId()))) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.holo.command_denied", "Use a Holo-Table in the Afterlife (operators may open it anywhere)"));
			return 0;
		}

		grants.put(player.getUUID(), new Grant(player.level().dimension(), null, System.currentTimeMillis()));
		Cyberblock.LOG.info("holo: {} opened the table (command)", player.getPlainTextName());
		send(player, true, true);
		return 1;
	}

	/** /cyberblock holo hide [on|off]: any player, for itself. */
	private static int hideCommand(final CommandSourceStack source, final @Nullable Boolean on) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return 0;
		}

		if (on == null) {
			player.sendSystemMessage(hidden(player)
				? Component.translatableWithFallback("cyberblock.holo.hide.on", "Your Holo-Table pin is hidden (outside matches)")
				: Component.translatableWithFallback("cyberblock.holo.hide.off", "Your Holo-Table pin is shown"));
			return 1;
		}

		hide(player, on);
		return 1;
	}
}
