// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import dev.cyberblock.client.net.ClientNet;
import dev.cyberblock.client.net.Relay;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jspecify.annotations.Nullable;

/**
 * The link to this PC's own Cyberblock.dll: a WebSocket server on 127.0.0.1 (port 25600, or -Dcyberblock.port). Wire protocol v1:
 * see shared/protocol.md. Since Phase 1 it is also the relay (megaplan section 1.2): every DLL message is LOCAL (handled on this
 * client: cam, key, hud, view, UI data), UP (sent on to the server through {@link Relay}: ground, dmg, hits, scan replies) or BOTH;
 * {@code cmd} is a test tool of a dev client only. The server's messages for the DLL come back as {@code cyberblock:down} and go out
 * through {@link #send} verbatim (ClientNet).
 *
 * <p>Minecraft to host: {"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3","ver":"1.0.0"} on connect, then {@code status},
 * {@code persp}, {@code flight}, {@code uiact}, {@code binds}, {@code survey}, {@code session} and every DOWN message. A host hello
 * with another proto closes the link. Client listeners ({@link #listen}) get every LOCAL and BOTH message on the link thread, after
 * this class's own handling.
 */
public final class HostLink extends WebSocketServer {
	private static final int PROTOCOL = 1;
	private static final int VER_MAX = 32;
	/** Where {@link #send} goes: the connected DLL once the link is listening (tests swap it). */
	private static volatile Consumer<String> out = json -> {};
	/** Client listeners by "t" (link thread), in registration order; one that throws is logged and the rest still run. */
	private static final Map<String, List<Consumer<JsonObject>>> listeners = new ConcurrentHashMap<>();
	/** The DLL's hello.ver; "" while no DLL is linked (no hello on an open connection yet). */
	private static volatile String dllVersion = "";
	/** The DLL's latest hello as it came: relayed UP once the server grants the host role. */
	private static volatile @Nullable String lastHello;

	private HostLink(final int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.setReuseAddr(true);
		this.setDaemon(true);
	}

	static void launch() {
		int port = Integer.getInteger("cyberblock.port", 25600);
		HostLink link = new HostLink(port);
		link.start();
		out = link::broadcast;
	}

	/** A message for this PC's DLL (any thread); dropped while none is connected. */
	public static void send(final String json) {
		out.accept(json);
	}

	/** Tests: sends go to {@code to} instead; returns the previous target. */
	static Consumer<String> swapOut(final Consumer<String> to) {
		Consumer<String> was = out;
		out = to;
		return was;
	}

	/** Adds a client listener for DLL messages of this type (LOCAL and BOTH only; the server handles UP). Runs on the link thread. */
	public static void listen(final String type, final Consumer<JsonObject> listener) {
		listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/** A DLL is connected and said hello. */
	public static boolean linked() {
		return !dllVersion.isEmpty();
	}

	/** The linked DLL's version (hello.ver, "unknown" from a DLL that sends none), "" when no DLL is linked: the handshake's {@code dll}. */
	public static String dllVersion() {
		return dllVersion;
	}

	public static @Nullable String lastHello() {
		return lastHello;
	}

	@Override
	public void onStart() {
		Cyberblock.LOG.info("host link listening on 127.0.0.1:{}", this.getPort());
	}

	@Override
	public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
		if (handshake.hasFieldValue("Origin")) {
			// a web page in a browser can reach 127.0.0.1 too, and "cmd" runs commands: only native programs may connect
			Cyberblock.LOG.warn("refusing a browser connection (Origin {})", handshake.getFieldValue("Origin"));
			conn.close(CloseFrame.POLICY_VALIDATION, "browsers are not allowed");
			return;
		}

		Cyberblock.LOG.info("host connected from {}", conn.getRemoteSocketAddress());
		conn.send(hello(version()));
	}

	/** This mod's version from fabric.mod.json (gradle.properties fills it in), for hello.ver and the handshake. */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(Cyberblock.ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	/** Our hello (shared/protocol.md); "ver" is the mod version (plan retest3 section 1.1), which the DLL compares with its own. */
	static String hello(final String version) {
		JsonObject hello = new JsonObject();
		hello.addProperty("t", "hello");
		hello.addProperty("proto", PROTOCOL);
		hello.addProperty("mod", Cyberblock.ID);
		hello.addProperty("mc", "26.3");
		hello.addProperty("ver", version);
		return hello.toString();
	}

	/** The DLL's hello.ver (megaplan section 1.2): at most 32 characters; absent or not a string from an older DLL: "unknown". */
	static String dllVer(final JsonObject hello) {
		if (hello.get("ver") instanceof JsonPrimitive v && v.isString() && !v.getAsString().isBlank()) {
			String ver = v.getAsString();
			return ver.length() > VER_MAX ? ver.substring(0, VER_MAX) : ver;
		}

		return "unknown";
	}

	@Override
	public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
		Cyberblock.LOG.info("host disconnected ({} {})", code, reason);
		if (this.getConnections().isEmpty()) {
			boolean was = linked();
			dllVersion = "";
			lastHello = null;
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> ClientInput.reset(minecraft));
			if (was) {
				ClientNet.linkChanged(); // hs with no DLL: the server ends the host session
			}
		}
	}

	@Override
	public void onMessage(final WebSocket conn, final String message) {
		JsonObject m;
		String t;
		try {
			m = JsonParser.parseString(message).getAsJsonObject();
			t = m.get("t").getAsString();
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
			return;
		}

		switch (Relay.route(t)) {
			case UP -> Relay.up(t, m, message);
			case BOTH -> {
				if (this.handle(conn, message, m, t)) {
					Relay.up(t, m, message);
				}

				dispatch(t, m);
			}
			case LOCAL -> {
				this.handle(conn, message, m, t);
				dispatch(t, m);
			}
			case DEV -> command(m);
		}
	}

	/** {@code cmd}: a server command from a test tool, only in a dev client (development run or -Dcyberblock.devtools) with its own world. */
	private static void command(final JsonObject m) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment() && !Boolean.getBoolean("cyberblock.devtools")) {
			Cyberblock.LOG.warn("ignoring cmd: not a dev client");
			return;
		}

		try {
			WorldBridge.command(m.get("c").getAsString());
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("bad cmd: {}", e.toString());
		}
	}

	/** This client's own handling; returns whether a BOTH message also goes UP now (a hello waits for the handshake when it changes the link). */
	private boolean handle(final WebSocket conn, final String message, final JsonObject m, final String t) {
		try {
			switch (t) {
				case "hello" -> {
					int proto = m.has("proto") ? m.get("proto").getAsInt() : -1;
					if (proto != PROTOCOL) {
						Cyberblock.LOG.warn("host speaks protocol {}, this mod speaks {}: closing the link", proto, PROTOCOL);
						conn.close(CloseFrame.PROTOCOL_ERROR, "protocol " + proto + " not supported");
						return false;
					}

					String ver = dllVer(m);
					boolean changed = !ver.equals(dllVersion);
					dllVersion = ver;
					lastHello = message;
					// the host starts every session in first person: tell it the view F5 chose (plan retest3 M4)
					ClientInput.sendPerspective();
					if (changed) {
						// a new hs; the hello goes UP when the server answers host (ClientNet)
						ClientNet.linkChanged();
						return false;
					}
				}
				case "cam" -> HostState.update(m);
				case "aim" -> HostState.updateAim(m);
				case "npcs", "stats" -> {
					// the client listeners (radar, HUD) are this client's whole part
				}
				default -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> {
						try {
							ClientInput.handle(minecraft, m);
						} catch (RuntimeException e) {
							Cyberblock.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
						}
					});
				}
			}
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
			return false;
		}

		return true;
	}

	/** The client listeners for this type (link thread). A listener that throws is logged, never fatal. */
	static void dispatch(final String type, final JsonObject message) {
		List<Consumer<JsonObject>> list = listeners.get(type);
		if (list == null) {
			return;
		}

		for (Consumer<JsonObject> listener : list) {
			try {
				listener.accept(message);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("listener for {} failed: {}", type, e.toString());
			}
		}
	}

	@Override
	public void onError(final WebSocket conn, final Exception e) {
		Cyberblock.LOG.warn("host link error", e);
	}
}
