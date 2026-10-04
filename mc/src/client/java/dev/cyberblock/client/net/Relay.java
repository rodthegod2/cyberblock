package dev.cyberblock.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * What the client does with each message from its own DLL (megaplan section 1.2), and the UP side of the relay: UP messages go to
 * the server as {@code cyberblock:up} payloads, split to fit the payload cap (section 1.3). The wire shape never changes: a chunk
 * is the same message with fewer cells or NPCs.
 */
public final class Relay {
	/** LOCAL stays on this PC; UP goes to the server only; BOTH is used here and relayed; DEV ({@code cmd}) is a dev client's test tool, never relayed. */
	public enum Route {
		LOCAL, UP, BOTH, DEV
	}

	private static final Set<String> UP = Set.of("ground", "unground", "clear", "dmg", "mobdmg", "pcells", "unlead", "killed",
		"scaninfo", "hackresult", "hackspread", "env", "vteleported");
	private static final Set<String> BOTH = Set.of("hello", "state", "aim", "npcs", "stats");
	/** Splittable messages: the array field and how many elements make one indivisible unit (a ground range is a quadruple). */
	private static final Map<String, Integer> UNIT = Map.of("ground", 4, "unground", 4, "terrain", 4, "npcs", 1);
	/** {@code stats} goes UP at most once a second (section 1.2); the newest waits for its turn. */
	static final long STATS_INTERVAL_NANOS = 1_000_000_000L;

	private static volatile BiConsumer<String, String> sink = (t, json) -> {};
	private static volatile int cap = 30000;
	private static final AtomicInteger sent = new AtomicInteger();
	private static final AtomicInteger dropped = new AtomicInteger();
	private static long lastStats = Long.MIN_VALUE;
	private static String pendingStats;

	private Relay() {
	}

	/** Every {@code t} the DLL sends (section 1.2); anything not listed there is LOCAL (handled or ignored here, never relayed). */
	public static Route route(final String t) {
		if (t.equals("cmd")) {
			return Route.DEV;
		}

		if (UP.contains(t)) {
			return Route.UP;
		}

		return BOTH.contains(t) ? Route.BOTH : Route.LOCAL;
	}

	/** Where UP chunks go (ClientNet: a payload on the client thread, only while this client is the host) and the json cap in bytes. */
	public static void install(final BiConsumer<String, String> to, final int jsonCap) {
		sink = to;
		cap = jsonCap;
	}

	/** One UP message from the DLL (link thread). {@code stats} is held to 1 Hz; the rest is split to the cap and sent in order. */
	public static synchronized void up(final String t, final JsonObject m, final String raw) {
		if (t.equals("stats")) {
			long now = System.nanoTime();
			if (lastStats != Long.MIN_VALUE && now - lastStats < STATS_INTERVAL_NANOS) {
				pendingStats = raw;
				return;
			}

			lastStats = now;
			pendingStats = null;
		}

		List<String> chunks = split(t, m, raw, cap);
		if (chunks.isEmpty()) {
			int n = dropped.incrementAndGet();
			if (n % 100 == 1) {
				Cyberblock.LOG.warn("relay: dropped {} over the {} byte cap ({} so far)", t, cap, n);
			}
		}

		for (String chunk : chunks) {
			send(t, chunk);
		}
	}

	/** A message this client builds itself ({@code hstate}, a held-back {@code hello}): sent as it is. */
	public static void upBuilt(final String t, final String json) {
		send(t, json);
	}

	/** Client tick: the newest held-back {@code stats}, once its second has passed. */
	public static synchronized void tick() {
		if (pendingStats != null && System.nanoTime() - lastStats >= STATS_INTERVAL_NANOS) {
			lastStats = System.nanoTime();
			send("stats", pendingStats);
			pendingStats = null;
		}
	}

	private static void send(final String t, final String json) {
		sent.incrementAndGet();
		sink.accept(t, json);
	}

	/** UP messages handed on since the last call (for the client's 5 s relay log line). */
	static int takeSent() {
		return sent.getAndSet(0);
	}

	/**
	 * The message as one or more chunks of at most {@code cap} UTF-8 bytes: unchanged when it fits; a splittable message (the
	 * {@code c} quadruples of {@code ground}, {@code unground} and {@code terrain}, the {@code npcs} list) is cut between whole units,
	 * every chunk keeping the other fields. Empty when it cannot fit (any other type over the cap, or one unit alone too big).
	 */
	public static List<String> split(final String t, final JsonObject m, final String raw, final int cap) {
		if (bytes(raw) <= cap) {
			return List.of(raw);
		}

		Integer unit = UNIT.get(t);
		String field = t.equals("npcs") ? "list" : "c";
		if (unit == null || !(m.get(field) instanceof JsonArray all) || all.size() % unit != 0) {
			return List.of();
		}

		JsonObject shell = m.deepCopy();
		shell.add(field, new JsonArray());
		int base = bytes(shell.toString());
		List<String> out = new ArrayList<>();
		JsonArray chunk = new JsonArray();
		int size = base;
		for (int i = 0; i < all.size(); i += unit) {
			int unitBytes = 0;
			for (int j = 0; j < unit; j++) {
				unitBytes += bytes(all.get(i + j).toString()) + 1; // the element and its comma
			}

			if (base + unitBytes > cap) {
				return List.of();
			}

			if (size + unitBytes > cap && !chunk.isEmpty()) {
				out.add(with(shell, field, chunk));
				chunk = new JsonArray();
				size = base;
			}

			for (int j = 0; j < unit; j++) {
				JsonElement e = all.get(i + j);
				chunk.add(e);
			}

			size += unitBytes;
		}

		if (!chunk.isEmpty()) {
			out.add(with(shell, field, chunk));
		}

		return out;
	}

	private static String with(final JsonObject shell, final String field, final JsonArray chunk) {
		JsonObject copy = shell.deepCopy();
		copy.add(field, chunk);
		return copy.toString();
	}

	static int bytes(final String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}
}
