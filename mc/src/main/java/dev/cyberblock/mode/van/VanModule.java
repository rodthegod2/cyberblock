package dev.cyberblock.mode.van;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.device.Device;
import dev.cyberblock.device.DeviceMap;
import dev.cyberblock.device.DeviceRules;
import dev.cyberblock.device.DeviceWire;
import dev.cyberblock.hack.actions.RangeRule;
import dev.cyberblock.holo.HoloLayers;
import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.net.TokenBucket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Hacker in the Van on the server (megaplan task 8-V1). A guest (never a player with a host session, Cyberpunk On or Off) sits in a
 * Van as netrunner and gets the Van screen: the linked host's area (V's surroundings as a miniature, with the figures and devices there
 * as targets), the host's photo feed (6-D1's Net Map feed, which the screen watches itself) and a drone to fly as eyes. The Hack Wheel on a target he can see runs in the owner's real game:
 * chips through Hacking (his RAM, cooldowns, the owner's {@code hacks} toggle and the Phase 0 (h) range rule), device actions as a
 * {@code devact} under the owner's {@code hacks} and device toggles, car and area hacks as 6-I1's {@code rhack}. The server decides
 * every "can see" again (the screen: the linked host's area; a drone: its sight with a clear line). Server thread.
 *
 * <p>Log lines: {@code van: <name> sits in the Van ...} (and {@code ... as netrunner again} when vanilla seated him with his Van at
 * login), {@code van: <name> has a Cyberpunk host session: put out of the Van ...}, {@code van: <name> left the Van}, {@code van: <name> linked to <host>},
 * {@code van: <name> launched|docked a drone ...}, {@code van: <name> -> <hack> on <target> of <host> (from the screen|drone)},
 * {@code van: <name> devact <act> <type> <id> of <host> -> request N}, {@code van: <host> devresult N <act> ok|refused (...)}.
 */
public final class VanModule {
	public static EntityType<VanEntity> VAN;
	public static EntityType<VanDrone> DRONE;
	public static Item VAN_ITEM;
	/** The Van item's id (8A's art, item model and English name are `hacker_van`). */
	static final String ITEM_ID = "hacker_van";
	/** The state goes to each netrunner this often (ticks). */
	static final int STATE_TICKS = 10;
	/** A drone takes off this high above the Van's roof. */
	static final double LAUNCH_HEIGHT = 2.6;
	/** `van` and `vhack` acts together, per player: this many a second, in bursts of up to {@link #ACTS_BURST} (section 1.6). */
	static final double ACTS_PER_SECOND = 10.0, ACTS_BURST = 20.0;

	/** A netrunner: the camera is on his drone (`piloting`); `dirty` sends his state at the end of this tick (`wantOpen`: opening the screen). */
	static final class Seat {
		boolean piloting;
		boolean opened;
		boolean dirty;
		boolean wantOpen;
	}

	/** A devact the Van sent: who asked, what, how far the device was from V. */
	private record Pending(UUID asker, String act, String what, double distance, long tick) {
	}

	private record PendingKey(UUID owner, int request) {
	}

	static final VanDevices devices = new VanDevices();
	private static final Map<UUID, Seat> seats = new HashMap<>();
	private static final Map<PendingKey, Pending> pending = new HashMap<>();
	private static final Map<UUID, Long> lastDeviceAct = new HashMap<>();
	private static final Map<UUID, TokenBucket> acts = new HashMap<>();
	private static @Nullable MinecraftServer server;
	private static int bad;

	private VanModule() {
	}

	/** Common init (Cyberblock.onInitialize): types, item, acts, listeners, the Holo-Table's drones layer, dev commands. */
	public static void init() {
		register();
		ServerNet.onAct(VanWire.T_VAN, VanModule::onVan);
		ServerNet.onAct(VanWire.T_VHACK, VanModule::onVhack);
		HostSessions.listen("devices", (s, m) -> {
			DeviceWire.Parsed p = DeviceWire.devices(m, DeviceMap::typeOf);
			if (p != null && s.active()) {
				devices.update(s.uuid(), p.devices(), tick());
			}
		});
		HostSessions.listen("devstate", (s, m) -> {
			DeviceWire.State st = DeviceWire.state(m);
			if (st != null) {
				devices.state(s.uuid(), st.id(), st.st(), st.fl());
			}
		});
		HostSessions.listen("devresult", VanModule::onDevResult);
		HostSessions.onEnd(s -> {
			devices.end(s.uuid());
			pending.keySet().removeIf(k -> k.owner().equals(s.uuid()));
		});
		HoloLayers.register("drones", (s, viewer) -> droneLayer(s));
		ServerTickEvents.END_SERVER_TICK.register(VanModule::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			seats.remove(handler.getPlayer().getUUID());
			lastDeviceAct.remove(handler.getPlayer().getUUID());
			acts.remove(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			Cyberblock.LOG.info(String.format(Locale.ROOT, "van: Hacker in the Van ready: links to hosts within %.0f blocks, the screen sees %.0f blocks around V, "
				+ "drones fly %.0f blocks from the Van for %d s and see %.0f blocks; chips go through the owner's hacks toggle and the Phase 0 (h) range rule "
				+ "(its own `hack range:` line says which path), device actions as devact, car and area hacks as rhack; the screen's photo feed is 6-D1's "
				+ "Net Map feed (the DLL's picture, or the replica fallback when none comes: its `feed:` lines say which path is active); "
				+ "%.0f van and vhack acts a second per player", VanRules.LINK_RANGE, VanRules.AREA_RADIUS, VanRules.DRONE_RANGE, VanRules.BATTERY / 20,
				VanRules.DRONE_SIGHT, ACTS_PER_SECOND));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			seats.clear();
			pending.clear();
			lastDeviceAct.clear();
			acts.clear();
			devices.clear();
			server = null;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> VanCommands.register(dispatcher));
	}

	private static void register() {
		ResourceKey<EntityType<?>> vanKey = ResourceKey.create(Registries.ENTITY_TYPE, id("van"));
		// tracked like a boat; the netrunner's client needs its position for the seat and the screen
		VAN = Registry.register(BuiltInRegistries.ENTITY_TYPE, vanKey, EntityType.Builder.<VanEntity>of(VanEntity::new, MobCategory.MISC)
			.sized(2.2F, 2.0F).clientTrackingRange(10).noLootTable().build(vanKey));
		ResourceKey<EntityType<?>> droneKey = ResourceKey.create(Registries.ENTITY_TYPE, id("van_drone"));
		// updated every tick: the pilot's camera sits in it
		DRONE = Registry.register(BuiltInRegistries.ENTITY_TYPE, droneKey, EntityType.Builder.<VanDrone>of(VanDrone::new, MobCategory.MISC)
			.sized(0.6F, 0.3F).eyeHeight(0.15F).noSave().noSummon().noLootTable().clientTrackingRange(8).updateInterval(1).build(droneKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(ITEM_ID));
		Item.Properties properties = new Item.Properties().stacksTo(1).rarity(Rarity.RARE)
			.component(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.cyberblock." + ITEM_ID, "Hacker Van"));
		VAN_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new VanItem(properties.setId(itemKey)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("cyberblock"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(VAN_ITEM));
	}

	static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, path);
	}

	static long tick() {
		MinecraftServer s = server;
		return s == null ? 0L : s.getTickCount();
	}

	// ---- the seat --------------------------------------------------------------------------------------------------------

	/** A right-click on a Van (VanEntity.interact): sit in, or, seated, open the screen. True when the click was the Van's. */
	static boolean use(final ServerPlayer player, final VanEntity van) {
		if (JoinLock.locked(player)) {
			return false;
		}

		if (player.getVehicle() == van) {
			Seat seat = seatIn(player, van);
			if (seat != null) {
				seat.dirty = true;
				seat.wantOpen = true;
			}

			return true;
		}

		// any host, Cyberpunk On or Off (section 1.1): his Steve follows V once it is on, and the relay checks his UP against Steve's place
		if (HostSessions.of(player) != null) {
			player.sendOverlayMessage(hostRefused());
			return true;
		}

		if (!van.getPassengers().isEmpty()) {
			player.sendOverlayMessage(text("taken", "Someone already sits in this Van").withStyle(ChatFormatting.RED));
			return true;
		}

		if (!player.startRiding(van)) {
			return false;
		}

		Seat seat = new Seat();
		seats.put(player.getUUID(), seat);
		HostSession host = linkedHost(van);
		Cyberblock.LOG.info("van: {} sits in the Van at {} as netrunner (host {})", player.getPlainTextName(), van.blockPosition().toShortString(),
			host == null ? "none in reach" : host.name());
		sendState(player, van, true);
		return true;
	}

	/** The netrunner got out (Shift, a teleport, the Van broken, a disconnect): his drone docks and his client lets go. */
	static void left(final ServerPlayer player, final VanEntity van) {
		dock(van, "the netrunner left");
		Seat seat = seats.remove(player.getUUID());
		if (seat != null) {
			ServerNet.ui(player, VanWire.UI_STATE, VanWire.state(new VanWire.State(false, -1, null, null, List.of(), null, van.charge(), List.of())));
			Cyberblock.LOG.info("van: {} left the Van", player.getPlainTextName());
		}
	}

	static void broken(final VanEntity van, final Entity by) {
		dock(van, "the Van was broken");
		Cyberblock.LOG.info("van: the Van at {} was broken by {}", van.blockPosition().toShortString(), by.getPlainTextName());
	}

	/**
	 * The seat of a player in a Van, made when he has none: vanilla saves a ridden Van with its player and seats him in it again at
	 * login (a relog, a server restart). A player with a Cyberpunk host session is put out instead (null): the Van is for guests.
	 */
	static @Nullable Seat seatIn(final ServerPlayer player, final VanEntity van) {
		if (HostSessions.of(player) != null) {
			player.stopRiding();
			player.sendOverlayMessage(hostRefused());
			Cyberblock.LOG.info("van: {} has a Cyberpunk host session: put out of the Van (it is for guests)", player.getPlainTextName());
			return null;
		}

		Seat seat = seats.get(player.getUUID());
		if (seat == null) {
			seat = new Seat();
			seat.dirty = true;
			seats.put(player.getUUID(), seat);
			HostSession host = linkedHost(van);
			Cyberblock.LOG.info("van: {} sits in the Van at {} as netrunner again (seated with it at login; host {})", player.getPlainTextName(),
				van.blockPosition().toShortString(), host == null ? "none in reach" : host.name());
		}

		return seat;
	}

	private static Component hostRefused() {
		return text("host", "The Van is for guests: a Cyberpunk host cannot sit in it").withStyle(ChatFormatting.RED);
	}

	/** At most {@link #ACTS_PER_SECOND} `van` and `vhack` acts a second per player; the rest are dropped (and counted). */
	private static boolean withinRate(final ServerPlayer player, final String what) {
		if (acts.computeIfAbsent(player.getUUID(), id -> new TokenBucket(ACTS_PER_SECOND, ACTS_BURST, Util::getMillis)).tryTake(1.0)) {
			return true;
		}

		warn(what + " over " + (int) ACTS_PER_SECOND + " a second from one player dropped");
		return false;
	}

	/** The seated netrunner's Van, or null. */
	static @Nullable VanEntity vanOf(final ServerPlayer player) {
		return player.getVehicle() instanceof VanEntity van && !van.isRemoved() ? van : null;
	}

	// ---- hosts -----------------------------------------------------------------------------------------------------------

	/** Active hosts whose V is in the Van's level and within link range, nearest first. */
	static List<HostSession> hostsInReach(final VanEntity van) {
		List<HostSession> out = new ArrayList<>();
		for (HostSession s : HostSessions.active()) {
			ServerPlayer v = s.player();
			if (v != null && v.level() == van.level() && VanRules.links(van.position(), v.position())) {
				out.add(s);
			}
		}

		out.sort(Comparator.comparingDouble(s -> VanRules.flat(Objects.requireNonNull(s.player()).position(), van.position())));
		return out;
	}

	/** The Van's linked host when it is in reach; else the nearest host in reach becomes the link (null when none). */
	static @Nullable HostSession linkedHost(final VanEntity van) {
		List<HostSession> reach = hostsInReach(van);
		for (HostSession s : reach) {
			if (s.uuid().equals(van.host())) {
				return s;
			}
		}

		HostSession first = reach.isEmpty() ? null : reach.getFirst();
		if (first != null) {
			van.setHost(first.uuid());
		}

		return first;
	}

	// ---- acts ------------------------------------------------------------------------------------------------------------

	private static void onVan(final ServerPlayer player, final JsonObject m) {
		if (!withinRate(player, "van op")) {
			return;
		}

		VanWire.Op op = VanWire.parseOp(m);
		VanEntity van = vanOf(player);
		Seat seat = van == null ? null : seatIn(player, van);
		if (op == null || seat == null) {
			warn("van op dropped (" + (op == null ? "bad" : "not seated") + ")");
			return;
		}

		switch (op) {
			case VanWire.LinkHost(UUID host) -> {
				HostSession s = hostsInReach(van).stream().filter(h -> h.uuid().equals(host)).findFirst().orElse(null);
				if (s == null) {
					player.sendOverlayMessage(text("no_host", "That host is not in the Van's reach").withStyle(ChatFormatting.RED));
				} else if (!host.equals(van.host())) {
					van.setHost(host);
					Cyberblock.LOG.info("van: {} linked to {}", player.getPlainTextName(), s.name());
				}
			}
			case VanWire.Launch() -> launch(player, van, seat);
			case VanWire.Pilot(boolean on) -> seat.piloting = on && drone(van) != null;
			case VanWire.Dock() -> dock(van, "docked by " + player.getPlainTextName());
		}

		seat.dirty = true; // one state at the end of this tick, however many ops came
	}

	private static void onVhack(final ServerPlayer player, final JsonObject m) {
		if (!withinRate(player, "vhack")) {
			return;
		}

		VanWire.Request r = VanWire.parseRequest(m);
		VanEntity van = vanOf(player);
		if (r == null || van == null || seatIn(player, van) == null) {
			warn("vhack dropped (" + (r == null ? "bad" : "not seated") + ")");
			return;
		}

		switch (r) {
			case VanWire.FigureHack f -> figureHack(player, van, f);
			case VanWire.DeviceRequest d -> deviceRequest(player, van, d);
		}
	}

	/** A chip on a person (or a car's driver) the netrunner can see: it becomes his lock, and the chip fires as from his wheel. */
	static boolean figureHack(final ServerPlayer player, final VanEntity van, final VanWire.FigureHack f) {
		Entity e = van.level().getEntity(f.eid());
		UUID owner = e == null ? null : Bridge.proxyOwnerOf.apply(e);
		long id = e == null ? 0L : Bridge.proxyIdOf.applyAsLong(e);
		if (!(e instanceof ProxyEntity proxy) || proxy.dead() || owner == null || id == 0L) {
			player.sendOverlayMessage(text("gone", "That target is gone").withStyle(ChatFormatting.RED));
			return false;
		}

		VanRules.Seen seen = sees(player, van, owner, e.getBoundingBox().getCenter(), null);
		if (seen == null) {
			player.sendOverlayMessage(text("unseen", "The Van cannot see that target (the host's area or your drone's sight)").withStyle(ChatFormatting.RED));
			return false;
		}

		Hacking.lock(player.getUUID(), owner, id, false, e.getX(), e.getY(), e.getZ(), e.getBbHeight(), proxy.label());
		if (!Hacking.useChip(player, f.hack())) {
			return false; // no chip, recharging, no RAM, the owner's toggle: Hacking told him why
		}

		HostSession s = HostSessions.of(owner);
		Cyberblock.LOG.info("van: {} -> {} on {} of {} (from the {})", player.getPlainTextName(), f.hack().wire(), proxy.label().isEmpty() ? id : proxy.label(),
			s == null ? owner : s.name(), seen.wire());
		return true;
	}

	/** A device action (or the lever chip) on a device the netrunner can see, under the owner's toggles and the range rule. */
	static boolean deviceRequest(final ServerPlayer player, final VanEntity van, final VanWire.DeviceRequest r) {
		Device d = devices.get(r.host(), r.dev(), tick());
		HostSession s = HostSessions.of(r.host());
		if (d == null || s == null || !s.active() || s.player() == null) {
			player.sendOverlayMessage(text("gone", "That target is gone").withStyle(ChatFormatting.RED));
			return false;
		}

		Vec3 at = new Vec3(d.x(), d.y(), d.z());
		VanRules.Seen seen = sees(player, van, r.host(), at.add(0.0, 0.5, 0.0), BlockPos.containing(at));
		if (seen == null) {
			player.sendOverlayMessage(text("unseen", "The Van cannot see that target (the host's area or your drone's sight)").withStyle(ChatFormatting.RED));
			return false;
		}

		if (r.act() == null) {
			Hacking.lock(player.getUUID(), r.host(), d.id(), true, d.x(), d.y(), d.z(), 1.0, d.cls());
			if (!Hacking.useChip(player, Hack.DEVICE)) {
				return false;
			}

			Cyberblock.LOG.info("van: {} -> device on {} {} of {} (from the {})", player.getPlainTextName(), d.type().id(), d.idText(), s.name(), seen.wire());
			return true;
		}

		if (!VanRules.deviceActAllowed(d.type(), r.act())) {
			warn("vhack devact " + r.act() + " on a " + d.type().id() + " dropped");
			return false;
		}

		if (!Permissions.allows(r.host(), player.getUUID(), Permissions.Toggle.HACKS) || !Permissions.allowsDevice(r.host(), player.getUUID(), d.type())) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.guest.blocked", "This Cyberpunk host does not let guests do that")
				.withStyle(ChatFormatting.RED));
			return false;
		}

		double distance = Objects.requireNonNull(s.player()).position().distanceTo(at);
		Hacking.announceRange();
		if (!RangeRule.SERVER.allows(distance, true)) {
			player.sendOverlayMessage(Hacking.outOfRange().copy().withStyle(ChatFormatting.RED));
			return false;
		}

		long now = tick();
		Long last = lastDeviceAct.get(player.getUUID());
		if (last != null && now - last < DeviceRules.USE_GAP_TICKS) {
			return false;
		}

		lastDeviceAct.put(player.getUUID(), now);
		DeviceRules.Use u = DeviceRules.request(d, r.act());
		if (u.outcome() != DeviceRules.Outcome.SEND) {
			player.sendOverlayMessage(Component.translatableWithFallback(u.key().isEmpty() ? "cyberblock.device.unsupported" : u.key(),
				u.fallback().isEmpty() ? "Night City cannot do that from Minecraft yet" : u.fallback()).withStyle(ChatFormatting.RED));
			return false;
		}

		int request = s.newRequest(player.getUUID());
		s.send(DeviceWire.devact(request, d, u.act()));
		pending.put(new PendingKey(r.host(), request), new Pending(player.getUUID(), u.act(), d.type().id() + " " + d.idText(), distance, now));
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.device.pending", "Waiting for Night City...").withStyle(ChatFormatting.AQUA));
		Cyberblock.LOG.info("van: {} devact {} {} {} of {} -> request {} (from the {})", player.getPlainTextName(), u.act(), d.type().id(), d.idText(), s.name(),
			request, seen.wire());
		return true;
	}

	/** devresult for one of the Van's devacts: the netrunner reads the answer (other packages' requests are theirs). */
	private static void onDevResult(final HostSession s, final JsonObject m) {
		DeviceWire.Result r = DeviceWire.result(m);
		Pending p = r == null ? null : pending.remove(new PendingKey(s.uuid(), r.id()));
		if (p == null) {
			return;
		}

		if (RangeRule.SERVER.observe(r.ok(), r.reason(), p.distance(), "devact " + p.act())) {
			Hacking.announceRange();
		}

		Cyberblock.LOG.info("van: {} devresult {} {} {}", s.name(), r.id(), p.act(), r.ok() ? "ok" : "refused (" + r.reason() + ")");
		ServerPlayer asker = server == null ? null : server.getPlayerList().getPlayer(p.asker());
		if (asker != null) {
			asker.sendOverlayMessage(r.ok()
				? text("dev_done", "Device: %s done", p.act()).withStyle(ChatFormatting.GREEN)
				: Component.translatableWithFallback("cyberblock.device.refused", "Night City refused: %s", r.reason()).withStyle(ChatFormatting.RED));
		}
	}

	// ---- seeing ----------------------------------------------------------------------------------------------------------

	/**
	 * From where the netrunner sees a target of `owner` at `p`: his drone (in sight, a clear line; `block` is the device's own cell,
	 * which may stop the line), else the screen (the linked host's area), as {@link #targets} marks it. Null: he cannot see it.
	 */
	static VanRules.@Nullable Seen sees(final ServerPlayer player, final VanEntity van, final UUID owner, final Vec3 p, final @Nullable BlockPos block) {
		VanDrone drone = drone(van);
		if (drone != null && Objects.equals(drone.pilot, player.getUUID()) && clearSight(drone, p, block)) {
			return VanRules.Seen.DRONE;
		}

		HostSession host = linkedHost(van);
		ServerPlayer v = host == null ? null : host.player();
		return host != null && v != null && host.uuid().equals(owner) && VanRules.inArea(p, v.position()) ? VanRules.Seen.SCREEN : null;
	}

	/** Within the drone's sight and nothing solid between its eye and the point (a device's own block may end the line). */
	static boolean clearSight(final VanDrone drone, final Vec3 p, final @Nullable BlockPos block) {
		Vec3 eye = drone.getEyePosition();
		if (!VanRules.inSight(p, eye)) {
			return false;
		}

		BlockHitResult hit = drone.level().clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone));
		return hit.getType() == HitResult.Type.MISS || block != null && hit.getBlockPos().equals(block)
			|| hit.getLocation().distanceToSqr(eye) >= p.distanceToSqr(eye) - 0.25;
	}

	/**
	 * Everything the netrunner can see now, capped: first what his drone sees (any host's figures and devices in its sight, nearest
	 * the drone first), then the rest of the linked host's area (nearest V first). A target both show counts as the drone's: it is in
	 * plain view.
	 */
	static List<VanWire.Target> targets(final ServerPlayer player, final VanEntity van, final @Nullable HostSession host) {
		ServerLevel level = (ServerLevel) van.level();
		List<VanWire.Target> out = new ArrayList<>();
		Set<Integer> droneFigures = new HashSet<>();
		Set<Long> droneDevices = new HashSet<>();
		long now = tick();
		VanDrone drone = drone(van);
		if (drone != null && Objects.equals(drone.pilot, player.getUUID())) {
			Vec3 eye = drone.getEyePosition();
			for (ProxyEntity e : level.getEntitiesOfClass(ProxyEntity.class, drone.getBoundingBox().inflate(VanRules.DRONE_SIGHT),
				e -> !e.dead() && e.owner() != null && clearSight(drone, e.getBoundingBox().getCenter(), null))) {
				out.add(figure(e, VanRules.Seen.DRONE));
				droneFigures.add(e.getId());
			}

			for (UUID owner : devices.owners()) {
				for (Device d : devices.of(owner, now)) {
					Vec3 at = new Vec3(d.x(), d.y(), d.z());
					if (clearSight(drone, at.add(0.0, 0.5, 0.0), BlockPos.containing(at))) {
						out.add(device(owner, d, VanRules.Seen.DRONE));
						if (owner.equals(host == null ? null : host.uuid())) {
							droneDevices.add(d.id());
						}
					}
				}
			}

			out.sort(Comparator.comparingDouble(t -> t.p().distanceToSqr(eye)));
		}

		ServerPlayer v = host == null ? null : host.player();
		if (host != null && v != null) {
			Vec3 c = v.position();
			List<VanWire.Target> area = new ArrayList<>();
			for (ProxyEntity e : level.getEntitiesOfClass(ProxyEntity.class, new AABB(c, c).inflate(VanRules.AREA_RADIUS, 64.0, VanRules.AREA_RADIUS),
				e -> !e.dead() && host.uuid().equals(e.owner()) && VanRules.inArea(e.position(), c) && !droneFigures.contains(e.getId()))) {
				area.add(figure(e, VanRules.Seen.SCREEN));
			}

			for (Device d : devices.of(host.uuid(), now)) {
				if (VanRules.inArea(new Vec3(d.x(), d.y(), d.z()), c) && !droneDevices.contains(d.id())) {
					area.add(device(host.uuid(), d, VanRules.Seen.SCREEN));
				}
			}

			area.sort(Comparator.comparingDouble(t -> t.p().distanceToSqr(c)));
			out.addAll(area);
		}

		return out.size() > VanRules.MAX_TARGETS ? List.copyOf(out.subList(0, VanRules.MAX_TARGETS)) : out;
	}

	private static VanWire.Target figure(final ProxyEntity e, final VanRules.Seen seen) {
		VanRules.Kind kind = e.getType() == ProxyEntities.VEHICLE ? VanRules.Kind.CAR : VanRules.Kind.PERSON;
		return new VanWire.Target(kind, e.getId(), null, 0L, e.label(), e.position(), e.hostile(), "", seen);
	}

	private static VanWire.Target device(final UUID owner, final Device d, final VanRules.Seen seen) {
		return new VanWire.Target(VanRules.Kind.DEVICE, -1, owner, d.id(), d.cls(), new Vec3(d.x(), d.y(), d.z()), false, d.type().id(), seen);
	}

	// ---- the state --------------------------------------------------------------------------------------------------------

	static void sendState(final ServerPlayer player, final VanEntity van, final boolean open) {
		Seat seat = seats.get(player.getUUID());
		if (seat == null) {
			return;
		}

		HostSession host = linkedHost(van);
		List<VanWire.Host> hosts = new ArrayList<>();
		for (HostSession s : hostsInReach(van)) {
			hosts.add(new VanWire.Host(s.uuid(), s.name(), Objects.requireNonNull(s.player()).position(),
				Permissions.allows(s.uuid(), player.getUUID(), Permissions.Toggle.HACKS)));
		}

		VanDrone drone = drone(van);
		VanWire.Drone d = drone == null ? null : new VanWire.Drone(drone.getId(), van.charge(), drone.position().distanceTo(van.position()), seat.piloting);
		ServerPlayer v = host == null ? null : host.player();
		VanWire.State state = new VanWire.State(open, van.getId(), host == null ? null : host.uuid(), v == null ? null : v.position(), hosts, d, van.charge(),
			targets(player, van, host));
		ServerNet.ui(player, VanWire.UI_STATE, VanWire.state(state));
		seat.opened |= open;
		seat.dirty = false;
		seat.wantOpen = false;
	}

	// ---- drones ----------------------------------------------------------------------------------------------------------

	/** The Van's drone in the air, or null. */
	static @Nullable VanDrone drone(final VanEntity van) {
		return van.drone >= 0 && van.level().getEntity(van.drone) instanceof VanDrone d && !d.isRemoved() ? d : null;
	}

	static boolean launch(final ServerPlayer player, final VanEntity van, final Seat seat) {
		if (drone(van) != null) {
			seat.piloting = true;
			return true;
		}

		if (!VanRules.mayLaunch(van.battery())) {
			player.sendOverlayMessage(text("charging", "The drone is charging: %s %%", Math.round(van.charge() * 100)).withStyle(ChatFormatting.RED));
			return false;
		}

		VanDrone drone = DRONE.create(van.level(), EntitySpawnReason.TRIGGERED);
		if (drone == null) {
			return false;
		}

		drone.snapTo(van.getX(), van.getY() + LAUNCH_HEIGHT, van.getZ(), player.getYRot(), player.getXRot());
		drone.van = van.getId();
		drone.pilot = player.getUUID();
		drone.setBattery(van.charge());
		van.level().addFreshEntity(drone);
		van.drone = drone.getId();
		seat.piloting = true;
		Cyberblock.LOG.info("van: {} launched a drone (battery {} %)", player.getPlainTextName(), Math.round(van.charge() * 100));
		return true;
	}

	/** The drone comes home (or was lost): it goes, the battery stays where it is. */
	static void dock(final VanEntity van, final String why) {
		VanDrone drone = drone(van);
		van.drone = -1;
		if (drone == null) {
			return;
		}

		drone.discard();
		ServerPlayer pilot = server == null || drone.pilot == null ? null : server.getPlayerList().getPlayer(drone.pilot);
		Seat seat = pilot == null ? null : seats.get(pilot.getUUID());
		if (seat != null) {
			seat.piloting = false;
			seat.dirty = true;
		}

		Cyberblock.LOG.info("van: {} docked a drone ({}; battery {} %)", pilot == null ? "a netrunner" : pilot.getPlainTextName(), why, Math.round(van.charge() * 100));
	}

	/** Every tick of a Van (server): the battery drains while its drone flies and refills while it is docked. */
	static void tickVan(final VanEntity van) {
		VanDrone drone = drone(van);
		van.setBattery(VanRules.battery(van.battery(), drone != null));
		if (drone != null && van.battery() == 0) {
			ServerPlayer pilot = van.netrunner();
			if (pilot != null) {
				pilot.sendOverlayMessage(text("battery_empty", "Drone battery empty: it flies home").withStyle(ChatFormatting.RED));
			}

			dock(van, "battery empty");
		}
	}

	/** Every tick of a drone (server): it follows its pilot's keys and view, held within its leash; it goes when its Van or pilot does. */
	static void tickDrone(final VanDrone drone) {
		ServerPlayer pilot = server == null || drone.pilot == null ? null : server.getPlayerList().getPlayer(drone.pilot);
		VanEntity van = drone.level().getEntity(drone.van) instanceof VanEntity v && !v.isRemoved() ? v : null;
		if (van == null || van.drone != drone.getId() || pilot == null || pilot.getVehicle() != van) {
			if (van != null && van.drone == drone.getId()) {
				van.drone = -1;
			}

			drone.discard();
			return;
		}

		Seat seat = seats.get(pilot.getUUID());
		VanRules.Keys keys = seat != null && seat.piloting ? keys(pilot.getLastClientInput()) : VanRules.Keys.NONE;
		Vec3 velocity = VanRules.step(drone.getDeltaMovement(), keys, pilot.getYRot());
		drone.move(MoverType.SELF, velocity);
		Vec3 home = van.position().add(0.0, LAUNCH_HEIGHT, 0.0);
		Vec3 held = VanRules.leash(drone.position(), home);
		if (held != drone.position()) {
			drone.setPos(held);
			velocity = Vec3.ZERO;
		}

		drone.setDeltaMovement(velocity);
		if (seat != null && seat.piloting) {
			drone.setYRot(pilot.getYRot());
			drone.setXRot(pilot.getXRot());
		}

		drone.setBattery(van.charge());
	}

	/** Vanilla's input of a seated player as drone keys: WASD, Space up, Ctrl (the sprint key) down; Shift is the seat's own (get out). */
	static VanRules.Keys keys(final Input in) {
		return new VanRules.Keys(in.forward(), in.backward(), in.left(), in.right(), in.jump(), in.sprint());
	}

	static void shotDown(final VanDrone drone, final Entity by) {
		VanEntity van = drone.level().getEntity(drone.van) instanceof VanEntity v ? v : null;
		if (van == null) {
			drone.discard();
			return;
		}

		van.setBattery(0);
		ServerPlayer pilot = van.netrunner();
		if (pilot != null) {
			pilot.sendOverlayMessage(text("shot_down", "Your drone was shot down: it needs a full recharge").withStyle(ChatFormatting.RED));
		}

		dock(van, "shot down by " + by.getPlainTextName());
	}

	/** The Holo-Table's drones layer (megaplan 7-T1): every drone in Night City, its battery as the weight. */
	static List<HoloWire.LayerItem> droneLayer(final MinecraftServer s) {
		List<HoloWire.LayerItem> out = new ArrayList<>();
		for (VanDrone d : s.overworld().getEntities(DRONE, e -> !e.isRemoved())) {
			ServerPlayer pilot = d.pilot == null ? null : s.getPlayerList().getPlayer(d.pilot);
			out.add(new HoloWire.LayerItem(d.getX(), d.getZ(), d.battery(), 0x40E0FF, pilot == null ? "Drone" : "Drone: " + pilot.getPlainTextName()));
		}

		return out;
	}

	// ---- time ------------------------------------------------------------------------------------------------------------

	/**
	 * Every tick: seats whose player left the Van go; a state goes to each seat that asked for one this tick (at most one per tick).
	 * Every {@link #STATE_TICKS}: every player in a Van gets a seat (or, a host, is put out) and his state, and devacts time out.
	 */
	private static void tick(final MinecraftServer s) {
		seats.keySet().removeIf(id -> {
			ServerPlayer p = s.getPlayerList().getPlayer(id);
			return p == null || vanOf(p) == null;
		});

		boolean all = s.getTickCount() % STATE_TICKS == 0;
		if (all) {
			for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) {
				VanEntity van = vanOf(p);
				Seat seat = van == null || JoinLock.locked(p) ? null : seatIn(p, van);
				if (seat != null) {
					seat.dirty = true;
				}
			}
		}

		for (Map.Entry<UUID, Seat> e : List.copyOf(seats.entrySet())) {
			ServerPlayer p = e.getValue().dirty ? s.getPlayerList().getPlayer(e.getKey()) : null;
			VanEntity van = p == null ? null : vanOf(p);
			if (van != null) {
				sendState(p, van, e.getValue().wantOpen);
			}
		}

		if (!all) {
			return;
		}

		long now = s.getTickCount();
		for (Iterator<Map.Entry<PendingKey, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Pending p = it.next().getValue();
			if (now - p.tick() >= DeviceRules.ANSWER_TICKS) {
				it.remove();
				ServerPlayer asker = s.getPlayerList().getPlayer(p.asker());
				if (asker != null) {
					asker.sendOverlayMessage(Component.translatableWithFallback("cyberblock.device.timeout", "Night City did not answer").withStyle(ChatFormatting.RED));
				}

				Cyberblock.LOG.info("van: devact {} for {} got no answer in 10 s", p.act(), p.what());
			}
		}
	}

	static @Nullable Seat seat(final UUID player) {
		return seats.get(player);
	}

	static MutableComponent text(final String key, final String english, final Object... args) {
		return Component.translatableWithFallback("cyberblock.van." + key, english, args);
	}

	/** A hostile client could make this a log flood: say it once, then every 100th time. */
	private static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("van: {} (seen {} times)", what, bad);
		}
	}
}
