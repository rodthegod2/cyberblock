package dev.cyberblock.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.content.Content;
import dev.cyberblock.net.admin.ServerAdmin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
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
	/** Phase features this server has (hs_ack.features): figures from Phase 3 (task 3-F1), devices from Phase 4 (task 4-M1). */
	static final int FEATURES = Payloads.FEATURE_FIGURES | Payloads.FEATURE_DEVICES;
	private static final Map<UUID, Conn> conns = new ConcurrentHashMap<>();
	private static final Map<String, BiConsumer<ServerPlayer, JsonObject>> actHandlers = new ConcurrentHashMap<>();
	private static volatile @Nullable MinecraftServer server;

	/** One connected player: what its last hs said and what the handshake decided. Server thread. */
	static final class Conn {
		final UUID uuid;
		final String name;
		final int joinTick;
		final RelayGuard guard;
		boolean handshaken, kicked;
		int proto;
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
		JoinLock.init();
		wireAdmin();
	}

	/**
	 * Package 2M's admin bits (/cyberblock status, /cyberblock hosts add|remove, joinHelp) read this package's connections, sessions,
	 * config and content through ServerAdmin's hooks (megaplan 2-M1; wired by the Wave A integrator).
	 */
	private static void wireAdmin() {
		ServerAdmin.versionOf = uuid -> {
			Conn c = conns.get(uuid);
			return c == null || !c.handshaken ? "" : "mod " + orNone(c.mod) + " dll " + orNone(c.dll);
		};
		ServerAdmin.rateOf = uuid -> {
			HostSession s = HostSessions.of(uuid);
			return s == null ? "" : String.format(Locale.ROOT, "up %.1f/s, dropped %d", s.upRate(), s.droppedDown());
		};
		ServerAdmin.contentCounts = Content::counts;
		ServerAdmin.configChanged = () -> {
			ServerConfig.load(configFile());
			MinecraftServer s = server;
			if (s != null) {
				JoinLock.reconfigured(s); // a joinPassword edited meanwhile locks or unlocks the players online
			}
		};
	}

	private static String orNone(final String version) {
		return version.isEmpty() ? "none" : version;
	}

	private static java.nio.file.Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Cyberblock.ID).resolve("server.json");
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
		ServerConfig.load(configFile());
		Permissions.use(s.overworld().getDataStorage().computeIfAbsent(HostsData.TYPE));
		JoinLock.started(s);
	}

	private static void stopping() {
		HostSessions.endAll("server stop");
		conns.clear();
		JoinLock.stopping();
		Permissions.use(new HostsData());
		server = null;
	}

	private static void onJoin(final ServerPlayer player, final MinecraftServer s) {
		conns.put(player.getUUID(), new Conn(player.getUUID(), player.getPlainTextName(), s.getTickCount()));
		JoinLock.onJoin(player, s);
		HostSessions.rosterChanged();
	}

	private static void onLeave(final ServerPlayer player) {
		HostSessions.end(player.getUUID(), "disconnect");
		conns.remove(player.getUUID());
		JoinLock.onLeave(player.getUUID());
		HostSessions.rosterChanged();
	}

	/**
	 * Why this player may not host, or null when it may (section 1.6, Handshake.hostRefusal): the singleplayer/LAN owner always
	 * may; a player still locked by the join password may not yet; a player who passed the join password counts as whitelisted.
	 */
	private static Handshake.@Nullable HostRefusal refusal(final MinecraftServer s, final ServerPlayer player, final ServerConfig config) {
		NameAndId id = player.nameAndId();
		PlayerList list = s.getPlayerList();
		return Handshake.hostRefusal(isOwner(s, player), JoinLock.locked(player.getUUID()), config.allowHosts(),
			list.isUsingWhitelist() && list.isWhiteListed(id), JoinLock.verified(s, player),
			config.hosts().isEmpty() || config.listsHost(player.getPlainTextName(), player.getUUID()));
	}

	private static void onHs(final ServerPlayer player, final Payloads.Hs hs) {
		MinecraftServer s = player.level().getServer();
		UUID uuid = player.getUUID();
		Conn conn = conns.computeIfAbsent(uuid, u -> new Conn(u, player.getPlainTextName(), s.getTickCount()));
		conn.proto = hs.proto();
		conn.mod = hs.mod();
		conn.dll = hs.dll();
		conn.wantHost = hs.wantHost();
		handshake(player, conn, s);
	}

	/**
	 * The join password decided again for this player (it logged in, or the password was turned on or off): its last hs is
	 * answered again, so a Cyberpunk host that waited becomes a host now (hs_ack host; its client then relays the DLL's hello) and
	 * a re-locked host goes back to guest. Nothing to do before the client's first hs.
	 */
	static void rehandshake(final ServerPlayer player) {
		Conn conn = conns.get(player.getUUID());
		if (conn != null && conn.handshaken && !conn.kicked) {
			handshake(player, conn, player.level().getServer());
		}
	}

	/** Decides the conn's last hs (section 1.5), answers hs_ack and grants or ends the host session. */
	private static void handshake(final ServerPlayer player, final Conn conn, final MinecraftServer s) {
		UUID uuid = conn.uuid;
		ServerConfig config = ServerConfig.get();
		String version = modVersion();
		Handshake.Result result = Handshake.decide(new Handshake.Input(conn.proto, conn.mod, conn.dll, conn.wantHost, version,
			refusal(s, player, config), HostSessions.hostsOtherThan(uuid), config.maxHosts()));
		if (result.host() && WorldCheck.refusesHosts()) {
			// a dedicated server whose overworld is not the Night City world type (WorldCheck; an operator can override it)
			result = new Handshake.Result(true, Payloads.ROLE_GUEST, WorldCheck.REFUSED);
			player.sendSystemMessage(WorldCheck.refusal());
		}

		// every refusal carries the server's joinHelp once (megaplan 2-M1; unchanged while joinHelp is empty)
		result = new Handshake.Result(result.ok(), result.role(), ServerAdmin.refusal(result.reason()));
		boolean wasHost = conn.result.host();
		conn.handshaken = true;
		conn.result = result;
		Cyberblock.LOG.info("handshake: {} mod {} dll {} -> {} ({})", conn.name, conn.mod, conn.dll.isEmpty() ? "none" : conn.dll, result.role(),
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
			session.versions(conn.mod, conn.dll);
		} else if (wasHost || HostSessions.isHost(uuid)) {
			HostSessions.end(uuid, conn.dll.isEmpty() ? "DLL link lost" : "role revoked");
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
		if (conn.kicked || JoinLock.locked(uuid)) {
			// the ups already queued behind a kick: one disconnect, with its reason, is enough; a locked player relays nothing
			return;
		}

		HostSession session = HostSessions.of(uuid);
		RelayGuard.Verdict verdict = conn.guard.check(session != null, up.t(), up.json(), player.getX(), player.getZ(), issued(session));
		if (verdict.kick() != null) {
			conn.kicked = true;
			Cyberblock.LOG.info("relay: {} kicked ({})", conn.name, session == null ? "guest up flood" : "overflow");
			HostSessions.end(uuid, "overflow");
			player.connection.disconnect(Component.translatableWithFallback("cyberblock.relay.kicked", verdict.kick()));
			return;
		}

		deliver(conn, session, verdict.messages());
	}

	private static IntPredicate issued(final @Nullable HostSession session) {
		return session == null ? id -> false : session.requests()::issued;
	}

	/** Hands the guard's ready messages to the session, in order. */
	private static void deliver(final Conn conn, final @Nullable HostSession session, final List<RelayGuard.Message> messages) {
		if (session == null) {
			return;
		}

		for (RelayGuard.Message m : messages) {
			session.countUp();
			if (!HostSessions.receive(session, m.t(), m.m())) {
				conn.guard.count(m.t(), "invalid", 1);
			}
		}
	}

	/** Each tick: messages a guard held for the host's position to catch up are checked again against the player's newer position. */
	private static void drainHeld(final MinecraftServer s) {
		for (Conn c : conns.values()) {
			ServerPlayer p = s.getPlayerList().getPlayer(c.uuid);
			if (p != null) {
				HostSession session = HostSessions.of(c.uuid);
				deliver(c, session, c.guard.drain(session != null, p.getX(), p.getZ(), issued(session)));
			}
		}
	}

	/** A host changes what guests may do to its game; the client hears the new bits in a fresh hs_ack. */
	private static void onPerms(final ServerPlayer player, final Payloads.Perms perms) {
		if (JoinLock.locked(player)) {
			return;
		}

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
		if (handler == null || m == null || JoinLock.locked(player)) {
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
		JoinLock.tick(s);
		drainHeld(s);
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
			if (c.handshaken || c.kicked || s.getTickCount() - c.joinTick < Handshake.TIMEOUT_TICKS) {
				continue;
			}

			ServerPlayer p = s.getPlayerList().getPlayer(c.uuid);
			// the singleplayer/LAN owner's client is the one running this server: it has the mod, never kick it
			if (p != null && !isOwner(s, p)) {
				c.kicked = true;
				Cyberblock.LOG.info("handshake: {} sent no hs in 10 s: disconnected (no Cyberblock mod)", c.name);
				p.connection.disconnect(Component.literal(Handshake.needsMod(config.joinHelp())));
			}
		}
	}

	/** The singleplayer or LAN owner (never on a dedicated server). */
	static boolean isOwner(final MinecraftServer s, final ServerPlayer player) {
		return !s.isDedicatedServer() && s.isSingleplayerOwner(player.nameAndId());
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

	/** cyberblock:content to one player (a clientSide content kind, section 1.7); a json over the cap is dropped and logged. */
	public static void content(final ServerPlayer player, final String kind, final String json) {
		if (!Payloads.fits(kind, Payloads.KIND_CAP) || !Payloads.fits(json, Payloads.DOWN_CAP)) {
			Cyberblock.LOG.warn("content: {} for {} over the cap: not sent", kind, player.getPlainTextName());
			return;
		}

		send(player, new Payloads.Content(kind, json));
	}

	/** The connected players' handshake state, for /cyberblock hosts. */
	static List<Conn> conns() {
		return List.copyOf(conns.values());
	}

	static @Nullable MinecraftServer server() {
		return server;
	}
}
