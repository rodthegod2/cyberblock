// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import java.net.InetSocketAddress;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The host's connection: a WebSocket server on 127.0.0.1 (port 25600, or -Dcyberblock.port). Wire protocol v1: see
 * shared/protocol.md.
 *
 * <p>Host to Minecraft (JSON, Minecraft coordinates):
 * <ul>
 * <li>{"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":vertical degrees,"fp":first person,"pl":[feet x,y,z],"h":body yaw}</li>
 * <li>{"t":"ground","c":[x,z,yBottom,yTop, ...]}: solid columns (barriers)</li>
 * <li>{"t":"clear"}: remove the barriers placed so far</li>
 * <li>{"t":"key","k":"use|attack|pick|inventory|drop|swap|escape","down":bool}</li>
 * <li>{"t":"slot","n":0-8}, {"t":"scroll","d":+-1}, {"t":"hud","hidden":bool}, {"t":"view","w":px,"h":px}</li>
 * </ul>
 * Minecraft to host: {"t":"hello",...} on connect. Unknown "t" values are ignored.
 */
public final class HostLink extends WebSocketServer {
	private static HostLink instance;

	private HostLink(final int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.setReuseAddr(true);
		this.setDaemon(true);
	}

	static void launch() {
		int port = Integer.getInteger("cyberblock.port", 25600);
		instance = new HostLink(port);
		instance.start();
		Cyberblock.events = message -> instance.broadcast(message);
	}

	@Override
	public void onStart() {
		Cyberblock.LOG.info("host link listening on 127.0.0.1:{}", this.getPort());
	}

	@Override
	public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
		Cyberblock.LOG.info("host connected from {}", conn.getRemoteSocketAddress());
		conn.send(String.format(Locale.ROOT, "{\"t\":\"hello\",\"v\":1,\"shm\":\"%s\",\"pid\":%d}", FrameExporter.NAME.replace("\\", "\\\\"), ProcessHandle.current().pid()));
	}

	@Override
	public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
		Cyberblock.LOG.info("host disconnected ({} {})", code, reason);
	}

	@Override
	public void onMessage(final WebSocket conn, final String message) {
		try {
			JsonObject m = JsonParser.parseString(message).getAsJsonObject();
			switch (m.get("t").getAsString()) {
				case "cam" -> HostState.update(m);
				case "ground" -> WorldBridge.solid(ints(m.getAsJsonArray("c")));
				case "clear" -> WorldBridge.clearSolid();
				default -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> ClientInput.handle(minecraft, m));
				}
			}
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
		}
	}

	@Override
	public void onError(final WebSocket conn, final Exception e) {
		Cyberblock.LOG.warn("host link error", e);
	}

	private static int[] ints(final JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}

		return out;
	}
}
