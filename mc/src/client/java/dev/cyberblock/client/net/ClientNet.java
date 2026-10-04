package dev.cyberblock.client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.Flight;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.atlas.SurveyClient;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.net.Payloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of the Minecraft connection (megaplan sections 1.3 and 1.5): the handshake ({@code hs} on join and whenever the
 * DLL link comes up, drops or reports another {@code ver}; {@code hs_ack} toasts and the {@code session} message to the DLL), UP
 * payloads (only while this client is the host), DOWN forwarding to the DLL, {@code hstate}, the roster and the reconnect screen.
 * Client thread unless a method says otherwise.
 */
public final class ClientNet {
	public enum Role {
		/** Not on a server, or no {@code hs_ack} yet. */
		NONE, HOST, GUEST
	}

	private static final int SESSION_REASON_MAX = 128;
	private static final Pattern TYPE = Pattern.compile("^\\s*\\{\\s*\"t\"\\s*:\\s*\"([^\"]{1,32})\"");
	private static final long LOG_NANOS = 5_000_000_000L;
	private static final long DROP_SCREEN_NANOS = 5_000_000_000L;
	/** Vanilla's disconnect reasons that are a refusal: the whitelist, a ban, an operator's kick, the same name logging in again. */
	private static final List<String> VANILLA_REFUSALS = List.of("multiplayer.disconnect.not_whitelisted", "multiplayer.disconnect.banned",
		"multiplayer.disconnect.kicked", "multiplayer.disconnect.duplicate_login");
	/** Messages this client builds for its own DLL (section 1.2, LOCAL): never accepted from the server over {@code cyberblock:down}. */
	private static final Set<String> CLIENT_ONLY = Set.of("hello", "status", "binds", "uiact", "persp", "flight", "survey", "session", "prefs");
	private static volatile Role role = Role.NONE;
	/** The DLL's hello still has to go UP for the current host grant (client thread). */
	private static boolean helloPending;
	private static volatile boolean connected;
	private static int seq;
	private static final HState hstate = new HState();
	private static final AtomicInteger downs = new AtomicInteger();
	private static final AtomicInteger ignored = new AtomicInteger();
	private static int notHost;
	private static long lastLog = System.nanoTime();
	/** The multiplayer server to reconnect to (null in singleplayer), the drop being handled and the retry count. */
	private static @Nullable ServerData target;
	private static boolean dropped, retrying;
	private static long droppedAt;
	private static int attempt;
	private static @Nullable Screen handled;

	private ClientNet() {
	}

	/** From the client entrypoint, after Payloads are registered (common init). */
	public static void init() {
		Relay.install((t, json) -> Minecraft.getInstance().execute(() -> sendUp(t, json)), Payloads.UP_CAP);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.HsAck.TYPE, (p, context) -> context.client().execute(() -> onAck(p)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Down.TYPE, (p, context) -> context.client().execute(() -> onDown(p.json())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Roster.TYPE, (p, context) -> context.client().execute(() -> Roster.set(
			p.list().stream().map(e -> new Roster.Entry(e.uuid(), e.name(), e.linked(), e.body(), e.team(), e.pvp(), e.downed(), e.hidden())).toList())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> onJoin(minecraft));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(ClientNet::onDisconnect));
		ClientTickEvents.END_CLIENT_TICK.register(ClientNet::tick);
		LoginScreen.register();
	}

	public static Role role() {
		return role;
	}

	private static void onJoin(final Minecraft minecraft) {
		connected = true;
		role = Role.NONE;
		seq = 0;
		hstate.reset();
		ServerData server = minecraft.getCurrentServer();
		target = minecraft.isLocalServer() || server == null ? null : server;
		dropped = retrying = false;
		attempt = 0;
		sendHs();
	}

	private static void onDisconnect() {
		boolean was = connected;
		connected = false;
		role = Role.NONE;
		Roster.clear();
		HostState.setAlignK(0);
		if (was) {
			// Cyberpunk is cleaned while Minecraft is off the server (section 1.2): no collision, puppets or pause are left behind
			HostLink.send(sessionMessage("none", "disconnected", 0));
			dropped = target != null;
			droppedAt = System.nanoTime();
		}
	}

	/** Any thread: the DLL link came up, dropped or reported another ver. On a server that is a new {@code hs}; off one, {@code session none}. */
	public static void linkChanged() {
		Minecraft.getInstance().execute(() -> {
			// a new or changed hello waits for the hs_ack (HostLink does not relay it); a dropped link leaves no hello to send
			helloPending = true;
			if (connected) {
				sendHs();
			} else if (HostLink.linked()) {
				HostLink.send(sessionMessage("none", "no world", 0));
			}
		});
	}

	private static void sendHs() {
		if (!connected) {
			return;
		}

		if (!ClientPlayNetworking.canSend(Payloads.Hs.TYPE)) {
			Cyberblock.LOG.info("handshake: the server has no Cyberblock networking");
			return;
		}

		String dll = HostLink.dllVersion();
		ClientPlayNetworking.send(new Payloads.Hs(Payloads.PROTO, HostLink.version(), dll, !dll.isEmpty()));
	}

	private static void onAck(final Payloads.HsAck ack) {
		Role now = ack.ok() && ack.role().equals("host") ? Role.HOST : ack.ok() ? Role.GUEST : Role.NONE;
		Role before = role;
		role = now;
		Cyberblock.LOG.info("handshake: server {} -> {}{}", ack.server(), ack.ok() ? ack.role() : "refused", ack.reason().isEmpty() ? "" : " (" + ack.reason() + ")");
		if (!ack.reason().isEmpty()) {
			UiLink.toast("warning", Component.translatableWithFallback("cyberblock.handshake.title", "Cyberblock").getString(), ack.reason(), 10.0);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.literal(ack.reason()));
			}
		}

		if (HostLink.linked()) {
			HostLink.send(sessionMessage(sessionState(ack.ok(), ack.role(), ack.reason()), ack.reason(), ack.perms()));
		}

		// the server starts a host session on the DLL's hello (section 1.4): relay it once per grant and once per changed hello, not on
		// every hs_ack (a perms change also sends one, and a second hello would restart the session and resync every block)
		helloPending = helloPending(helloPending, before, now);
		String hello = HostLink.lastHello();
		if (helloPending && HostLink.linked() && hello != null) {
			Relay.upBuilt("hello", hello);
			helloPending = false;
		}

		if (now != Role.HOST && before == Role.HOST) {
			hstate.reset();
		}
	}

	/** Whether the DLL's hello still has to go UP after an hs_ack: on a new host grant, or while a changed hello waits; never when not host. */
	static boolean helloPending(final boolean pending, final Role before, final Role now) {
		return now == Role.HOST && (pending || before != Role.HOST);
	}

	/** {@code cyberblock:down}: the server's message for this client's DLL, forwarded verbatim; {@code survey_cmd} is this client's own. */
	static void onDown(final String json) {
		downs.incrementAndGet();
		String t = type(json);
		switch (downAction(role, HostLink.linked(), t)) {
			case IGNORE -> {
				if (ignored.incrementAndGet() % 100 == 1) {
					Cyberblock.LOG.info("relay: ignored a down message while not the linked host ({} so far)", ignored.get());
				}

				return;
			}
			case DROP -> {
				if (ignored.incrementAndGet() % 100 == 1) {
					Cyberblock.LOG.warn("relay: dropped a down message of a client-only type {} ({} ignored so far)", t, ignored.get());
				}

				return;
			}
			case SURVEY -> {
				SurveyClient.onCommand(json);
				return;
			}
			case FORWARD -> {
			}
		}

		if ("align".equals(t)) {
			try {
				HostState.setAlignK(JsonParser.parseString(json).getAsJsonObject().get("k").getAsInt());
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("relay: bad align from the server: {}", e.toString());
			}
		}

		HostLink.send(json);
	}

	/** DOWN reaches the DLL only for the host role with the DLL linked (section 1.3: a client that is not the host ignores it). */
	public static boolean forwardsDown(final Role role, final boolean linked) {
		return role == Role.HOST && linked;
	}

	enum DownAction {
		/** Not the linked host (section 1.3). */
		IGNORE,
		/** A client-only LOCAL type, or no {@code t}: never forwarded to the DLL (section 1.2). */
		DROP,
		/** {@code survey_cmd}: this client's own, run by SurveyClient, never forwarded. */
		SURVEY,
		FORWARD
	}

	/** What a DOWN message does: the role check comes first, so a guest whose DLL is linked never runs a survey either. */
	static DownAction downAction(final Role role, final boolean linked, final @Nullable String t) {
		if (!forwardsDown(role, linked)) {
			return DownAction.IGNORE;
		}

		if ("survey_cmd".equals(t)) {
			return DownAction.SURVEY;
		}

		return t == null || CLIENT_ONLY.contains(t) ? DownAction.DROP : DownAction.FORWARD;
	}

	/** The message's {@code t} without parsing a megabyte of blocks: the server writes {@code t} first; otherwise a full parse. */
	static @Nullable String type(final String json) {
		Matcher m = TYPE.matcher(json.length() > 64 ? json.substring(0, 64) : json);
		if (m.find()) {
			return m.group(1);
		}

		try {
			return JsonParser.parseString(json).getAsJsonObject().get("t").getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** One UP chunk (client thread): dropped unless this client is the host (the server kicks a guest that keeps sending {@code up}). */
	private static void sendUp(final String t, final String json) {
		if (!connected || role != Role.HOST || !ClientPlayNetworking.canSend(Payloads.Up.TYPE)) {
			notHost++;
			return;
		}

		ClientPlayNetworking.send(new Payloads.Up(++seq, t, json));
	}

	/** {@code state} for the DLL's {@code session} after a handshake result (section 1.5): a refused host role is "refused". */
	public static String sessionState(final boolean ok, final String role, final String reason) {
		if (!ok) {
			return "refused";
		}

		if (role.equals("host")) {
			return "host";
		}

		return reason.isEmpty() ? "guest" : "refused";
	}

	/** {"t":"session","state":...,"reason":... (at most 128 bytes),"perms":{...}} (section 1.2); perms bits 1 explosions, 2 damage, 4 hacks, 8 devices, 16 spawns. */
	public static String sessionMessage(final String state, final String reason, final int perms) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "session");
		m.addProperty("state", state);
		m.addProperty("reason", truncate(reason, SESSION_REASON_MAX));
		JsonObject p = new JsonObject();
		String[] names = {"explosions", "damage", "hacks", "devices", "spawns"};
		for (int i = 0; i < names.length; i++) {
			p.addProperty(names[i], (perms & 1 << i) != 0);
		}

		m.add("perms", p);
		return m.toString();
	}

	/** At most {@code max} UTF-8 bytes, cut between characters. */
	static String truncate(final String s, final int max) {
		if (Relay.bytes(s) <= max) {
			return s;
		}

		int end = s.length();
		while (end > 0 && Relay.bytes(s.substring(0, end)) > max) {
			end--;
			if (end > 0 && Character.isLowSurrogate(s.charAt(end))) {
				end--;
			}
		}

		return s.substring(0, end);
	}

	private static void tick(final Minecraft minecraft) {
		Relay.tick();
		if (connected && role == Role.HOST) {
			HState.Snapshot now = HostState.hstate(Flight.launching());
			if (hstate.due(now, System.nanoTime())) {
				Relay.upBuilt("hstate", now.json());
			}
		}

		long nanos = System.nanoTime();
		if (nanos - lastLog >= LOG_NANOS) {
			double seconds = (nanos - lastLog) / 1.0E9;
			lastLog = nanos;
			int up = Relay.takeSent(), down = downs.getAndSet(0);
			if (up > 0 || down > 0) {
				Cyberblock.LOG.info(String.format(java.util.Locale.ROOT, "relay: up %.1f msgs/s, down %.1f msgs/s%s", up / seconds, down / seconds,
					notHost > 0 ? " (" + notHost + " up held back: not the host)" : ""));
			}

			notHost = 0;
		}

		watchDisconnect(minecraft);
	}

	/** A disconnect screen after a drop from a multiplayer server (or after a failed retry) becomes the reconnect screen. */
	private static void watchDisconnect(final Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		// a drop shows its disconnect screen within moments; a retry lasts while vanilla's connect screen does
		if (dropped && System.nanoTime() - droppedAt > DROP_SCREEN_NANOS) {
			dropped = false;
		}

		if (retrying && !(screen instanceof ConnectScreen || screen instanceof DisconnectedScreen || screen instanceof ReconnectScreen)) {
			retrying = false;
		}

		if (!(screen instanceof DisconnectedScreen) || screen == handled || target == null || !(dropped || retrying)) {
			return;
		}

		handled = screen;
		String text = screen.getNarrationMessage().getString();
		if (!Reconnect.worthRetrying(text, refusals()) || Reconnect.gaveUp(attempt)) {
			Cyberblock.LOG.info("reconnect: not retrying ({})", Reconnect.gaveUp(attempt) ? "10 minutes are up" : "refused by the server");
			cancelReconnect();
			return;
		}

		int delay = Reconnect.delaySeconds(attempt);
		Cyberblock.LOG.info("reconnect: in {} s ({})", delay, text.length() > 120 ? text.substring(0, 120) : text);
		attempt++;
		minecraft.gui.setScreen(new ReconnectScreen(screen, target, delay));
	}

	/** Disconnect texts that are a refusal, not a lost connection, in the player's language. */
	private static List<String> refusals() {
		List<String> out = new ArrayList<>(List.of("Cyberblock version mismatch", "needs the Cyberblock modpack", "too many messages", "join password"));
		for (String vanilla : VANILLA_REFUSALS) {
			out.add(Component.translatable(vanilla).getString());
		}

		return out;
	}

	/** ReconnectScreen is connecting again: the next disconnect screen counts as a failed retry. */
	static void retrying() {
		retrying = true;
		dropped = false;
	}

	/** Cancel, or no retry: the disconnect screen stays. */
	static void cancelReconnect() {
		target = null;
		dropped = retrying = false;
		attempt = 0;
	}
}
