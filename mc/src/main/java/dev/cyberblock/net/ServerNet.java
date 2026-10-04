package dev.cyberblock.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.jspecify.annotations.Nullable;

/**
 * Server half of the custom-payload protocol (megaplan section 1.3): registers the payload types and the server receivers, runs
 * the handshake of section 1.5, routes UP messages to the sessions, flushes their DOWN queues and sends the roster. Receivers run on
 * the server thread (see Payloads for the verified Fabric names and threads).
 */
public final class ServerNet {
	/** Phase features this server has (hs_ack.features): none in Phase 1. */
	static final int FEATURES = 0;
	private static final Map<UUID, Conn> conns = new ConcurrentHashMap<>();
	private static final Map<String, BiConsumer<ServerPlayer, JsonObject>> actHandlers = new ConcurrentHashMap<>();
	private static volatile @Nullable MinecraftServer server;

	/** One connected player: what its last hs said and what the handshake decided. Server thread. */
	static final class Conn {
		final UUID uuid;
		final String name;
		final int joinTick;
		final RelayGuard guard;
		boolean handshaken;
		String mod = "", dll = "";
		boolean wantHost;
		Handshake.Result result = new Handshake.Result(true, Payloads.ROLE_GUEST, "");

		Conn(final UUID uuid, final String name, final int joinTick) {
			this.uuid = uuid;
			this.name = name;
			this.joinTick = joinTick;
			ServerConfig config = ServerConfig.get();
			this.guard = new RelayGuard(config.rate(), config.sanityRadius(), System::currentTimeMillis);
		}
	}

	private ServerNet() {
	}

	/** Common init, once (Cyberblock.onInitialize): payload types for both sides, the server receivers and the server events. */
	public static void init() {
		Payloads.register();
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Hs.TYPE, (payload, context) -> onHs(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Up.TYPE, (payload, context) -> onUp(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Perms.TYPE, (payload, context) -> onPerms(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Act.TYPE, (payload, context) -> onAct(context.player(), payload));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.getPlayer(), s));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onLeave(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STARTED.register(ServerNet::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopping());
		ServerTickEvents.END_SERVER_TICK.register(ServerNet::tick);
	}

	/**
	 * Adds the handler for one cyberblock:act type (scan, hack, devact, meetup, spawn, breach...; each phase lists its own). One
	 * handler per type; it runs on the server thread with the asking player and the parsed json object.
	 */
	public static void onAct(final String t, final BiConsumer<ServerPlayer, JsonObject> handler) {
		if (actHandlers.put(t, handler) != null) {
			Cyberblock.LOG.warn("act handler for {} replaced", t);
		}
	}

	/** This mod's version from fabric.mod.json (gradle.properties fills it in). */
	public static String modVersion() {
		return FabricLoader.getInstance().getModContainer(Cyberblock.ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	private static void started(final MinecraftServer s) {
		server = s;
		ServerConfig.load(FabricLoader.getInstance().getConfigDir().resolve(Cyberblock.ID).resolve("server.json"));
		Permissions.use(s.overworld().getDataStorage().computeIfAbsent(HostsData.TYPE));
	}

	private static void stopping() {
		HostSessions.endAll("server stop");
		conns.clear();
		Permissions.use(new HostsData());
		server = null;
	}

	private static void onJoin(final ServerPlayer player, final MinecraftServer s) {
		conns.put(player.getUUID(), new Conn(player.getUUID(), player.getPlainTextName(), s.getTickCount()));
		HostSessions.rosterChanged();
	}

	private static void onLeave(final ServerPlayer player) {
		HostSessions.end(player.getUUID(), "disconnect");
		conns.remove(player.getUUID());
		HostSessions.rosterChanged();
	}

	/** Why this player may not host, or null when it may: the singleplayer/LAN owner always may (section 1.6). */
	private static Handshake.@Nullable HostRefusal refusal(final MinecraftServer s, final ServerPlayer player, final ServerConfig config) {
		NameAndId id = player.nameAndId();
		if (!s.isDedicatedServer() && s.isSingleplayerOwner(id)) {
			return null;
		}

		if (!config.allowHosts()) {
			return Handshake.HostRefusal.NOT_ALLOWED;
		}

		PlayerList list = s.getPlayerList();
		if (!list.isUsingWhitelist() || !list.isWhiteListed(id)) {
			return Handshake.HostRefusal.NOT_WHITELISTED;
		}

		if (!config.hosts().isEmpty() && !config.listsHost(player.getPlainTextName(), player.getUUID())) {
			return Handshake.HostRefusal.NOT_LISTED;
		}

		return null;
	}

	private static void onHs(final ServerPlayer player, final Payloads.Hs hs) {
		MinecraftServer s = player.level().getServer();
		UUID uuid = player.getUUID();
		Conn conn = conns.computeIfAbsent(uuid, u -> new Conn(u, player.getPlainTextName(), s.getTickCount()));
		ServerConfig config = ServerConfig.get();
		String version = modVersion();
		Handshake.Result result = Handshake.decide(new Handshake.Input(hs.proto(), hs.mod(), hs.dll(), hs.wantHost(), version,
			refusal(s, player, config), HostSessions.hostsOtherThan(uuid), config.maxHosts()));
		boolean wasHost = conn.result.host();
		conn.handshaken = true;
		conn.mod = hs.mod();
		conn.dll = hs.dll();
		conn.wantHost = hs.wantHost();
		conn.result = result;
		Cyberblock.LOG.info("handshake: {} mod {} dll {} -> {} ({})", conn.name, hs.mod(), hs.dll().isEmpty() ? "none" : hs.dll(), result.role(),
			result.reason());
		sendAck(player, conn);
		if (!result.ok()) {
			player.connection.disconnect(Component.literal(result.reason()));
			return;
		}

		if (result.host()) {
			HostSession session = HostSessions.grant(uuid, conn.name, s, json -> {
				ServerPlayer p = s.getPlayerList().getPlayer(uuid);
				if (p != null) {
					send(p, new Payloads.Down(json));
				}
			});
			session.versions(hs.mod(), hs.dll());
		} else if (wasHost || HostSessions.isHost(uuid)) {
			HostSessions.end(uuid, hs.dll().isEmpty() ? "DLL link lost" : "role revoked");
		}
	}

	/** hs_ack with the conn's current decision and the player's current guest toggles. */
	private static void sendAck(final ServerPlayer player, final Conn conn) {
		Handshake.Result r = conn.result;
		String reason = Payloads.fits(r.reason(), Payloads.REASON_CAP) ? r.reason() : r.reason().substring(0, Payloads.REASON_CAP / 3);
		send(player, new Payloads.HsAck(Payloads.PROTO, cap(modVersion(), Payloads.VERSION_CAP), r.ok(), r.role(), reason,
			Permissions.of(conn.uuid).bits(), ServerConfig.get().maxHosts(), FEATURES));
	}

	private static String cap(final String s, final int bytes) {
		return Payloads.fits(s, bytes) ? s : s.substring(0, bytes / 3);
	}

	/** Every up goes through the connection's RelayGuard (section 1.6) before the session and its listeners see it. */
	private static void onUp(final ServerPlayer player, final Payloads.Up up) {
		UUID uuid = player.getUUID();
		Conn conn = conns.computeIfAbsent(uuid, u -> new Conn(u, player.getPlainTextName(), player.level().getServer().getTickCount()));
		HostSession session = HostSessions.of(uuid);
		RelayGuard.Verdict verdict = conn.guard.check(session != null, up.t(), up.json(), player.getX(), player.getZ(),
			session == null ? id -> false : session.requests()::issued);
		if (verdict.kick() != null) {
			Cyberblock.LOG.info("relay: {} kicked ({})", conn.name, session == null ? "guest up flood" : "overflow");
			HostSessions.end(uuid, "overflow");
			player.connection.disconnect(Component.translatableWithFallback("cyberblock.relay.kicked", verdict.kick()));
			return;
		}

		if (verdict.message() == null || session == null) {
			return;
		}

		session.countUp();
		if (!HostSessions.receive(session, up.t(), verdict.message())) {
			conn.guard.count(up.t(), "invalid", 1);
		}
	}

	/** A host changes what guests may do to its game; the client hears the new bits in a fresh hs_ack. */
	private static void onPerms(final ServerPlayer player, final Payloads.Perms perms) {
		setPerms(player, new Permissions(perms.perms(), perms.devices()));
	}

	/** Stores the player's guest toggles and tells its client (hs_ack again, same role and reason). Server thread. */
	static void setPerms(final ServerPlayer player, final Permissions perms) {
		Permissions.set(player.getUUID(), perms);
		Conn conn = conns.get(player.getUUID());
		if (conn != null && conn.handshaken) {
			sendAck(player, conn);
		}
	}

	private static void onAct(final ServerPlayer player, final Payloads.Act act) {
		BiConsumer<ServerPlayer, JsonObject> handler = actHandlers.get(act.t());
		JsonObject m = parse(act.json());
		if (handler == null || m == null) {
			Cyberblock.LOG.debug("act {} from {} dropped", act.t(), player.getPlainTextName());
			return;
		}

		try {
			handler.accept(player, m);
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("act {} from {} failed: {}", act.t(), player.getPlainTextName(), e.toString());
		}
	}

	static @Nullable JsonObject parse(final String json) {
		try {
			JsonElement e = JsonParser.parseString(json);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void tick(final MinecraftServer s) {
		HostSessions.flushAll();
		if (HostSessions.takeRosterDirty()) {
			Payloads.Roster roster = roster(s);
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				send(p, roster);
			}
		}

		if (s.getTickCount() % 20 == 0) {
			kickSilent(s);
			for (Conn c : conns.values()) {
				for (String line : c.guard.drainLog(c.name)) {
					Cyberblock.LOG.info(line);
				}
			}
		}

		if (s.getTickCount() % 100 == 0) {
			for (HostSession session : HostSessions.sessionsInAnyState()) {
				session.sampleRates(5.0);
			}
		}
	}

	/** Section 1.5 step 3: a client that sent no hs within 10 s has no Cyberblock mod; with requireMod it is disconnected. */
	private static void kickSilent(final MinecraftServer s) {
		ServerConfig config = ServerConfig.get();
		if (!config.requireMod()) {
			return;
		}

		for (Conn c : List.copyOf(conns.values())) {
			if (!c.handshaken && s.getTickCount() - c.joinTick >= Handshake.TIMEOUT_TICKS) {
				ServerPlayer p = s.getPlayerList().getPlayer(c.uuid);
				c.handshaken = true; // once
				if (p != null) {
					Cyberblock.LOG.info("handshake: {} sent no hs in 10 s: disconnected (no Cyberblock mod)", c.name);
					p.connection.disconnect(Component.literal(Handshake.needsMod(config.joinHelp())));
				}
			}
		}
	}

	/** Every online player (at most 200): linked = a LINKED or ACTIVE session; the rest are Phase 1 defaults. */
	static Payloads.Roster roster(final MinecraftServer s) {
		List<Payloads.RosterEntry> list = new ArrayList<>();
		boolean pvp = ServerConfig.get().pvpDefault();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (list.size() == Payloads.ROSTER_CAP) {
				break;
			}

			HostSession session = HostSessions.of(p);
			boolean linked = session != null && session.state() != HostSession.State.NONE;
			list.add(new Payloads.RosterEntry(p.getUUID(), cap(p.getPlainTextName(), Payloads.NAME_CAP), linked, "male", -1, pvp, false, false));
		}

		return new Payloads.Roster(List.copyOf(list));
	}

	/** Sends a payload to a player that has the Cyberblock mod; false (and nothing sent) when its client cannot receive it. */
	static boolean send(final ServerPlayer player, final CustomPacketPayload payload) {
		if (!ServerPlayNetworking.canSend(player, payload.type())) {
			return false;
		}

		ServerPlayNetworking.send(player, payload);
		return true;
	}

	/** cyberblock:ui to one player (server data for a screen); a json over the cap is dropped and logged. */
	public static void ui(final ServerPlayer player, final String t, final String json) {
		if (!Payloads.fits(t, Payloads.T_CAP) || !Payloads.fits(json, Payloads.DOWN_CAP)) {
			Cyberblock.LOG.warn("ui {} for {} over the cap: dropped", t, player.getPlainTextName());
			return;
		}

		send(player, new Payloads.Ui(t, json));
	}

	/** The connected players' handshake state, for /cyberblock hosts. */
	static List<Conn> conns() {
		return List.copyOf(conns.values());
	}

	static @Nullable MinecraftServer server() {
		return server;
	}
}
