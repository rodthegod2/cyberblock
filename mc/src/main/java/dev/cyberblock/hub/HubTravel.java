package dev.cyberblock.hub;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;
import org.jspecify.annotations.Nullable;

/**
 * Entering and leaving the Afterlife (megaplan 7-H1) and keeping a linked player's Cyberpunk in step with it. Entering (the command,
 * the pause screen's button or a hub portal) remembers where the player was and puts them at the hub's arrival point; leaving takes
 * them back. Once a second every online player's place is compared with what its DLL last heard ({@link HubRules#sync}): a linked
 * player in the hub gets {@code hub in:true} (full-screen Minecraft) and {@code park on:true} (V parked), and a linked player out of
 * it, however they left, the reverse. A dropped link or a session end needs nothing sent: the DLL cleans itself on {@code session
 * none} (it releases a parked V, megaplan 1.2); a new session (the next hello) starts from "out of the hub" and is told again. A
 * player who fell off the hub is put back at the arrival point. Server thread only.
 *
 * <p>Log lines: {@code hub: <name> entered (from <level> <x> <y> <z>)}, {@code hub: <name> left (to <level> <x> <y> <z>)},
 * {@code hub: <name> <- hub in:<b>, park on:<b>}, {@code hub: park path: <which>} (once a run, Phase 0 g), {@code hub: <name> V not
 * parked (<reason>)}.
 */
public final class HubTravel {
	/** A park on waits this long for its parked answer before the path line says it is unconfirmed. */
	static final long PARK_ANSWER_MS = 10_000L;
	/** What each linked player's DLL was last told (true: in the hub), by player UUID; absent: nothing since its last hello. */
	private static final Map<UUID, Boolean> told = new HashMap<>();
	/** Park ons waiting for their parked answer: player UUID -> when sent (ms). */
	private static final Map<UUID, Long> awaiting = new HashMap<>();
	private static final Map<UUID, Long> lastPortal = new HashMap<>();
	private static boolean pathLogged;

	private HubTravel() {
	}

	static void init() {
		HostSessions.listen("parked", HubTravel::onParked);
		// a new hello: the DLL starts out of the hub (its session start cleaned it), so it is told again if the player is in it
		HostSessions.onStart(s -> {
			told.remove(s.uuid());
			ServerPlayer p = s.player();
			if (p != null) {
				sync(p);
			}
		});
		HostSessions.onEnd(s -> {
			told.remove(s.uuid());
			awaiting.remove(s.uuid());
		});
	}

	static void stopping() {
		told.clear();
		awaiting.clear();
		lastPortal.clear();
		pathLogged = false;
	}

	/** Is the player in the Afterlife? */
	public static boolean inHub(final ServerPlayer player) {
		return Afterlife.is(player.level());
	}

	/** {@code /cyberblock hub} and the pause screen's button: in when out, out when in. */
	static int toggle(final ServerPlayer player) {
		return inHub(player) ? leave(player) : enter(player);
	}

	/** A hub portal's right-click: the same as the command, at most once every two seconds. */
	static void portal(final ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long last = lastPortal.get(player.getUUID());
		if (last != null && now - last < HubRules.PORTAL_COOLDOWN_MS) {
			return;
		}

		lastPortal.put(player.getUUID(), now);
		toggle(player);
	}

	/** Takes the player to the arrival point; 0 (and the reason shown) when they may not go. */
	static int enter(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel hub = Afterlife.level(server);
		HubData data = HubData.get(server);
		double[] spawn = data.spawn();
		HostSession s = HostSessions.of(player);
		boolean drives = s != null && s.active();
		String why = JoinLock.locked(player) ? "cyberblock.hub.locked"
			: HubRules.enterRefusal(hub == null || spawn == null, inHub(player), drives, drives && s.held(), drives && s.vehicle());
		if (why != null) {
			player.sendSystemMessage(refusal(why));
			return 0;
		}

		HubData.Return from = HubData.Return.of(player);
		data.setReturn(player.getUUID(), from);
		player.setNoGravity(false);
		player.teleportTo(hub, spawn[0], spawn[1], spawn[2], Set.of(), (float) spawn[3], 0.0F, true);
		player.resetFallDistance();
		sync(player);
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.welcome",
			"Welcome to the Afterlife. Leave with the portal, the pause screen or /cyberblock hub"));
		Cyberblock.LOG.info(String.format(Locale.ROOT, "hub: %s entered (from %s %.1f %.1f %.1f)", player.getPlainTextName(), from.level(), from.x(),
			from.y(), from.z()));
		return 1;
	}

	/** Takes the player back to where they came from (or the world spawn when that is unknown). */
	static int leave(final ServerPlayer player) {
		if (!inHub(player)) {
			player.sendSystemMessage(refusal("cyberblock.hub.not_in"));
			return 0;
		}

		MinecraftServer server = player.level().getServer();
		HubData.Return back = HubData.get(server).takeReturn(player.getUUID());
		ServerLevel to = back == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(back.level())));
		if (to == null || Afterlife.is(to)) {
			LevelData.RespawnData spawn = server.getRespawnData();
			to = server.findRespawnDimension();
			BlockPos p = spawn.pos();
			back = new HubData.Return(to.dimension().identifier().toString(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, spawn.yaw(), 0.0F,
				back != null && back.noGravity());
		}

		player.teleportTo(to, back.x(), back.y(), back.z(), Set.of(), back.yaw(), back.pitch(), true);
		player.setNoGravity(back.noGravity());
		player.resetFallDistance();
		sync(player);
		Cyberblock.LOG.info(String.format(Locale.ROOT, "hub: %s left (to %s %.1f %.1f %.1f)", player.getPlainTextName(), back.level(), back.x(), back.y(),
			back.z()));
		return 1;
	}

	/** Once a second: every player's DLL in step with their place, fallen players back on the floor, unanswered parks noted. */
	static void tick(final MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}

		HubData data = HubData.get(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sync(p);
			if (inHub(p)) {
				rescue(p, data);
			} else if (data.hasReturn(p.getUUID())) {
				data.takeReturn(p.getUUID()); // left some other way (a death, a teleport): nothing to go back to
			}
		}

		long now = System.currentTimeMillis();
		awaiting.entrySet().removeIf(e -> {
			if (now - e.getValue() < PARK_ANSWER_MS) {
				return false;
			}

			logPath(null);
			return true;
		});
	}

	/** Tells a linked player's DLL what changed (nothing for a guest, nothing when it already knows). */
	static void sync(final ServerPlayer player) {
		HostSession s = HostSessions.of(player);
		if (s == null || s.state() == HostSession.State.NONE) {
			return;
		}

		boolean in = inHub(player);
		List<String> messages = HubRules.sync(in, told.get(player.getUUID()));
		if (messages.isEmpty()) {
			return;
		}

		messages.forEach(s::send);
		told.put(player.getUUID(), in);
		if (in) {
			awaiting.put(player.getUUID(), System.currentTimeMillis());
		} else {
			awaiting.remove(player.getUUID());
		}

		Cyberblock.LOG.info("hub: {} <- hub in:{}, park on:{}", s.name(), in, in);
	}

	/** Fell off the hub: back to the arrival point. */
	private static void rescue(final ServerPlayer player, final HubData data) {
		double[] spawn = data.spawn();
		if (spawn != null && player.getY() < data.voidY()) {
			player.teleportTo(player.level(), spawn[0], spawn[1], spawn[2], Set.of(), (float) spawn[3], 0.0F, true);
			player.resetFallDistance();
		}
	}

	/** {@code parked} from a host's DLL: the answer to the park on it was sent when it entered. */
	private static void onParked(final HostSession s, final JsonObject m) {
		HubRules.Parked a = HubRules.parked(m);
		if (a == null) {
			return;
		}

		awaiting.remove(s.uuid());
		if (!a.on() && a.ok()) {
			return; // the answer to a release
		}

		logPath(a);
		if (!a.ok()) {
			Cyberblock.LOG.info("hub: {} V not parked ({})", s.name(), a.reason());
			ServerPlayer p = s.player();
			if (p != null) {
				p.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.not_parked",
					"Your Cyberpunk could not park V (%s): V stays where it is, unprotected", a.reason()));
			}
		}
	}

	/** The Phase 0 (g) path, once a server run (a refusal is logged each time on its own line). */
	private static void logPath(final HubRules.@Nullable Parked answer) {
		if (!pathLogged) {
			pathLogged = true;
			Cyberblock.LOG.info("hub: park path: {}", HubRules.parkPath(answer));
		}
	}

	private static Component refusal(final String key) {
		return switch (key) {
			case "cyberblock.hub.missing" -> Component.translatableWithFallback(key, "The Afterlife is not open on this server");
			case "cyberblock.hub.already" -> Component.translatableWithFallback(key, "You are in the Afterlife already");
			case "cyberblock.hub.not_in" -> Component.translatableWithFallback(key, "You are not in the Afterlife");
			case "cyberblock.hub.held" -> Component.translatableWithFallback(key, "Not now: the game holds V (a scene or a ride)");
			case "cyberblock.hub.vehicle" -> Component.translatableWithFallback(key, "Not now: V is in a vehicle");
			case "cyberblock.hub.locked" -> Component.translatableWithFallback(key, "Type /login <password> first");
			default -> Component.translatableWithFallback(key, "Not now");
		};
	}
}
