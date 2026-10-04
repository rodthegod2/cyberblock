package dev.cyberblock.device;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.device.block.DeviceHooks;
import dev.cyberblock.device.block.ShopWire;
import dev.cyberblock.replica.ReplicaRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The device layer on the server (megaplan task 4-M1): per owner session, the devices UP become their section 1.11 blocks (through
 * device_map) in the overworld, kept out of the replica (ReplicaRules.excluded), updated from devstate, and gone 10 s after they left
 * every list or when the session ends. A player's right-click on a device block (or {@code cyberblock:act t:"devact"} with its
 * {@code pos}) goes through the rules and the owner's guest toggles (the devices toggle and the type bit) to a devact DOWN to the owner
 * only; the block changes only on devresult ok or the next devstate. Vending machines are Minecraft shops paid in eddies (Vending).
 * Cyberblock.onInitialize calls init() once, after ServerNet.init (so sessions end before this layer forgets its devices at a stop).
 * Server thread only.
 */
public final class DeviceModule {
	private static final DeviceRegistry registry = new DeviceRegistry(DeviceModule::look);
	private static final Map<PendingKey, Pending> pending = new HashMap<>();
	private static final Map<UUID, Long> lastUse = new HashMap<>();
	/** Per owner that streams devices: what changed since its last summary line (devices added, cells set, state changes). */
	private static final Map<UUID, int[]> changes = new HashMap<>();
	private static volatile @Nullable MinecraftServer server;

	/** A devact waiting for its devresult: the request id is the owner session's. */
	private record PendingKey(UUID owner, int request) {
	}

	private record Pending(DeviceRegistry.Key device, UUID asker, String act, long tick) {
	}

	private DeviceModule() {
	}

	public static void init() {
		DeviceMap.register();
		Shops.register();
		ReplicaRules.excludedCells = registry::excluded;
		HostSessions.listen("devices", DeviceModule::devices);
		HostSessions.listen("devstate", DeviceModule::devstate);
		HostSessions.listen("devresult", DeviceModule::devresult);
		HostSessions.onEnd(DeviceModule::end);
		// 4B's device blocks and the vending screen's cyberblock:act devact (buy) come here (rule 0.13: built against wb/p4-b)
		DeviceHooks.install(DeviceModule::hook);
		UseBlockCallback.EVENT.register(DeviceModule::onUse);
		// survival players never break a device block; a creative break frees the cell and the next list puts the block back
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
			!(level instanceof ServerLevel) || player.isCreative() || registry.at(pos.asLong()) == null);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (level instanceof ServerLevel) {
				registry.broken(pos.asLong());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(DeviceModule::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(DeviceModule::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopping());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> DeviceCommands.register(dispatcher));
	}

	/** The one line saying which runtime paths are active: 4B's blocks or vanilla stand-ins, device_map files or the built-in table. */
	private static void started(final MinecraftServer s) {
		server = s;
		int files = DeviceMap.contentEntries();
		Cyberblock.LOG.info("devices: blocks from {}; device_map {}", DeviceBlocks.customBlocksPresent() ? "the cyberblock device blocks (4B)"
			: "vanilla stand-ins (the cyberblock device blocks are missing)", files > 0 ? files + " files (built-in defaults for other classes)"
			: "built-in defaults (no device_map files)");
	}

	/** After the sessions ended (their devices are gone already): nothing of this world stays. */
	private static void stopping() {
		registry.clear();
		pending.clear();
		lastUse.clear();
		changes.clear();
		Vending.clear();
		server = null;
	}

	/** How a device is drawn: its device_map entry, and its blocks (4B's, or the vanilla stand-in). */
	static DeviceRegistry.Look look(final Device d) {
		DeviceMap.Entry e = DeviceMap.entry(d.cls(), d.type());
		List<BlockState> attachments = new ArrayList<>();
		for (DeviceMap.Attachment a : e.attachments()) {
			attachments.add(DeviceBlocks.resolveAttachment(a.block()));
		}

		return new DeviceRegistry.Look(e, new DeviceShape.Blocks(DeviceBlocks.resolve(e.blockFor(d), d.type()), attachments));
	}

	/** The overworld as cells; every change is Night City's own (host ground: never a player's block, never logged as one). */
	static DeviceRegistry.Cells cells(final ServerLevel level) {
		return new DeviceRegistry.Cells() {
			@Override
			public BlockState get(final long cell) {
				return level.getBlockState(BlockPos.of(cell));
			}

			@Override
			public void set(final long cell, final BlockState state) {
				WorldBridge.asHostGround(() -> level.setBlock(BlockPos.of(cell), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
			}
		};
	}

	private static @Nullable ServerLevel level() {
		MinecraftServer s = server;
		return s == null ? null : s.overworld();
	}

	private static long now() {
		MinecraftServer s = server;
		return s == null ? 0L : s.getTickCount();
	}

	static DeviceRegistry registry() {
		return registry;
	}

	// ---- UP ----------------------------------------------------------------------------------------------------------------

	/** devices: every entry is the device's current data; a session that is not active builds nothing (protocol.md, Phase 4). */
	static void devices(final HostSession s, final JsonObject m) {
		ServerLevel level = level();
		DeviceWire.Parsed p = DeviceWire.devices(m, DeviceMap::typeOf);
		if (level == null || !s.active() || p == null) {
			return;
		}

		int[] c = changes.computeIfAbsent(s.uuid(), u -> {
			Cyberblock.LOG.info("devices: {} streams its devices ({} in the first list)", s.name(), p.devices().size());
			return new int[3];
		});
		DeviceRegistry.Change change = registry.update(s.uuid(), p.devices(), now(), cells(level));
		c[0] += change.added();
		c[1] += change.cells();
	}

	/** devstate: the device's block shows its new state (an NPC opening a door opens it in Minecraft). */
	static void devstate(final HostSession s, final JsonObject m) {
		ServerLevel level = level();
		DeviceWire.State st = DeviceWire.state(m);
		if (level == null || st == null) {
			return;
		}

		if (registry.state(s.uuid(), st.id(), st.st(), st.fl(), cells(level)) != null) {
			changes.computeIfAbsent(s.uuid(), u -> new int[3])[2]++;
			Cyberblock.LOG.debug("devices: {} devstate {} st={} fl={} by {}", s.name(), Long.toUnsignedString(st.id()), st.st(), st.fl(), st.by());
		}
	}

	/** devresult: ok shows the act's state at once; a refusal keeps the old state and tells the asker why. */
	static void devresult(final HostSession s, final JsonObject m) {
		DeviceWire.Result r = DeviceWire.result(m);
		Pending p = r == null ? null : pending.remove(new PendingKey(s.uuid(), r.id()));
		if (p == null) {
			return; // a late answer after its timeout, or not ours
		}

		DeviceRegistry.Tracked t = registry.get(p.device().owner(), p.device().id());
		ServerLevel level = level();
		if (r.ok()) {
			Device want = t == null ? null : DeviceRules.expected(t.device(), p.act());
			if (want != null && level != null) {
				registry.state(s.uuid(), want.id(), want.st(), want.fl(), cells(level));
			}
		} else {
			tell(p.asker(), refusal(r.reason()));
		}

		Cyberblock.LOG.info("devices: {} devresult {} for {} {}", s.name(), r.id(), p.act(), r.ok() ? "ok" : "refused (" + r.reason() + ")");
	}

	private static Component refusal(final String reason) {
		return switch (reason) {
			case "unsupported" -> Component.translatableWithFallback("cyberblock.device.unsupported", "Night City cannot do that with this device yet");
			case "quest locked" -> Component.translatableWithFallback("cyberblock.device.quest_locked", "This device belongs to a mission and cannot be used now");
			default -> Component.translatableWithFallback("cyberblock.device.refused", "Night City refused: %s", reason);
		};
	}

	// ---- players -----------------------------------------------------------------------------------------------------------

	/** A right-click on a device block: the server decides (the vanilla use, a door swinging by itself, never runs). */
	private static InteractionResult onUse(final Player player, final Level level,
		final InteractionHand hand, final BlockHitResult hit) {
		if (!(level instanceof ServerLevel) || !(player instanceof ServerPlayer actor) || hand != InteractionHand.MAIN_HAND || actor.isSpectator()) {
			return InteractionResult.PASS;
		}

		DeviceRegistry.Tracked t = registry.at(hit.getBlockPos().asLong());
		if (t == null || actor.isShiftKeyDown() && !actor.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS; // not a device, or building against it
		}

		use(actor, t, hit.getBlockPos(), DeviceRules.use(t.device(), actor.isShiftKeyDown()), -1, 1);
		return InteractionResult.SUCCESS;
	}

	/**
	 * 4B's way in (DeviceHooks): a use of one of its device blocks ({@code open, close, on, off, enable, disable, friendly, call, shop,
	 * view}) or the vending screen's {@code cyberblock:act t:"devact"} {@code {pos, act:"buy", line}} (4B checked the act and the reach).
	 * A right-click on a registered device never gets here: onUse decided it first.
	 */
	static void hook(final ServerPlayer player, final BlockPos pos, final String act, final JsonObject extra) {
		DeviceRegistry.Tracked t = registry.at(pos.asLong());
		if (t == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.device.no_link", "Night City is not connected: devices cannot be used"));
			return;
		}

		int row = act.equals("buy") ? Vending.row(ShopWire.buyLine(extra)) : -1;
		DeviceRules.Use u = switch (act) {
			case "shop", "buy" -> DeviceRules.request(t.device(), "buy");
			case "view" -> DeviceRules.use(t.device(), false);
			default -> DeviceRules.request(t.device(), act);
		};
		if (act.equals("buy") && row < 0) {
			return; // a bad line: dropped
		}

		use(player, t, pos, u, row, 1);
	}

	/**
	 * The devtools path ({@code /cyberblock test devact <x> <y> <z> <act> [row]}): what a right-click ({@code use}, or {@code sneak}),
	 * an act or a buy of that row does.
	 */
	static boolean test(final ServerPlayer player, final BlockPos pos, final String act, final int row) {
		DeviceRegistry.Tracked t = registry.at(pos.asLong());
		if (t == null) {
			return false;
		}

		DeviceRules.Use u = act.equals("sneak") ? DeviceRules.use(t.device(), true) : DeviceRules.request(t.device(), act);
		use(player, t, pos, u, row, 1);
		return true;
	}

	/**
	 * One use of a device by a player: the owner's guest toggles first (the devices toggle and the type bit; a host on its own game,
	 * another host and the world are always allowed), then the rules' outcome: a devact DOWN to the owner, the vending shop, or the
	 * refusal's message. row is a vending row for a buy (-1: show the shop).
	 */
	private static void use(final ServerPlayer player, final DeviceRegistry.Tracked t, final BlockPos pos, final DeviceRules.Use u, final int row, final int times) {
		long now = now();
		Long last = lastUse.get(player.getUUID());
		if (last != null && now - last < DeviceRules.USE_GAP_TICKS) {
			return;
		}

		lastUse.put(player.getUUID(), now);
		Device d = t.device();
		UUID owner = t.key().owner();
		String who = player.getPlainTextName(), what = d.type().id() + " " + d.idText();
		if (!Permissions.allowsDevice(owner, player.getUUID(), d.type())) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.guest.blocked", "That host does not let guests do this to their Night City"));
			Cyberblock.LOG.info("devices: {} {} {} -> blocked (the host's guest toggle)", who, u.act().isEmpty() ? "use" : u.act(), what);
			return;
		}

		switch (u.outcome()) {
			case REFUSED -> {
				player.sendOverlayMessage(Component.translatableWithFallback(u.key(), u.fallback()));
				Cyberblock.LOG.info("devices: {} used {} -> refused here ({})", who, what, u.key());
			}
			case SHOP -> {
				if (row < 0) {
					Vending.open(player, t, pos);
				} else {
					Vending.buy(player, t, pos, row, times);
				}
			}
			case SEND -> {
				HostSession s = HostSessions.of(owner);
				if (s == null || s.state() == HostSession.State.NONE) {
					player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.device.no_link", "Night City is not connected: devices cannot be used"));
					return;
				}

				int request = s.newRequest(player.getUUID());
				s.send(DeviceWire.devact(request, d, u.act()));
				pending.put(new PendingKey(owner, request), new Pending(t.key(), player.getUUID(), u.act(), now));
				player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.device.pending", "Waiting for Night City..."));
				Cyberblock.LOG.info("devices: {} {} {} of {} -> devact {}", who, u.act(), what, s.name(), request);
			}
		}
	}

	private static void tell(final UUID player, final Component message) {
		MinecraftServer s = server;
		ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(player);
		if (p != null) {
			p.sendOverlayMessage(message);
		}
	}

	// ---- time --------------------------------------------------------------------------------------------------------------

	/** Once a second: devices gone for 10 s go, devacts unanswered for 10 s are told; every 5 s a summary line per host. */
	private static void tick(final MinecraftServer s) {
		long now = s.getTickCount();
		if (now % 20 != 0) {
			return;
		}

		DeviceRegistry.Change gone = registry.expire(now, cells(s.overworld()));
		if (gone.removed() > 0) {
			Cyberblock.LOG.info("devices: {} gone (no list named them for 10 s)", gone.removed());
		}

		for (Iterator<Map.Entry<PendingKey, Pending>> it = pending.entrySet().iterator(); it.hasNext();) {
			Pending p = it.next().getValue();
			if (now - p.tick() >= DeviceRules.ANSWER_TICKS) {
				it.remove();
				tell(p.asker(), Component.translatableWithFallback("cyberblock.device.timeout", "Night City did not answer"));
				Cyberblock.LOG.info("devices: devact for {} {} got no answer in 10 s", p.act(), Long.toUnsignedString(p.device().id()));
			}
		}

		if (now % 100 == 0) {
			for (Map.Entry<UUID, int[]> e : changes.entrySet()) {
				int[] c = e.getValue();
				if (c[0] + c[1] + c[2] > 0) {
					HostSession session = HostSessions.of(e.getKey());
					Cyberblock.LOG.info("devices: {} tracks {} (added {}, cells set {}, state changes {})", session == null ? e.getKey() : session.name(),
						registry.size(e.getKey()), c[0], c[1], c[2]);
					Arrays.fill(c, 0);
				}
			}
		}
	}

	/** The session ended: all its devices and requests go now. */
	static void end(final HostSession s) {
		ServerLevel level = level();
		DeviceRegistry.Change c = level == null ? DeviceRegistry.Change.NONE : registry.end(s.uuid(), cells(level));
		pending.keySet().removeIf(k -> k.owner().equals(s.uuid()));
		if (changes.remove(s.uuid()) != null || c.removed() > 0) {
			Cyberblock.LOG.info("devices: {} ended: {} devices removed", s.name(), c.removed());
		}
	}
}
