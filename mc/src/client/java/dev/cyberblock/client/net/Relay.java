package dev.cyberblock.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.atlas.AtlasWire;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

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
		"scaninfo", "hackresult", "hackspread", "env", "vteleported", "devices", "devstate", "devresult");
	/** {@code terrain} is BOTH from Phase 3 (megaplan 3-R2): this client's Atlas keeps it, and the server's shared map grows from it. */
	private static final Set<String> BOTH = Set.of("hello", "state", "aim", "npcs", "stats", "terrain", "feed"); // feed: Phase 6 (1.9 row 6), the photo feed
	/** Splittable messages: the array field and how many elements make one indivisible unit (a ground range is a quadruple). */
	private static final Map<String, Integer> UNIT = Map.of("ground", 4, "unground", 4, "terrain", 4, "npcs", 1);
	/** {@code stats} goes UP at most once a second (section 1.2); the newest waits for its turn. */
	static final long STATS_INTERVAL_NANOS = 1_000_000_000L;
	/**
	 * {@code terrain} goes UP in batches: the DLL sends at most 64 cells a message and a survey tour hundreds of messages a second,
	 * while the server takes 30 a second (burst 200) of up to 1024 cells each (3R's NightCityMap.MAX_CELLS). The client tick sends
	 * one batch at most every 50 ms (20 a second), the oldest cells first, each under the payload cap so it is never split.
	 */
	static final long TERRAIN_INTERVAL_NANOS = 50_000_000L;
	static final int TERRAIN_BATCH = 1024;
	/** Cells waiting at most (two of the DLL's busiest survey frames); past it the oldest are dropped and counted. */
	static final int TERRAIN_BUFFER = 32_768;
	/** {"t":"terrain","c":[]} */
	private static final int TERRAIN_SHELL_BYTES = 22;

	private static volatile BiConsumer<String, String> sink = (t, json) -> {};
	private static volatile int cap = 30000;
	private static final AtomicInteger sent = new AtomicInteger();
	private static final AtomicInteger dropped = new AtomicInteger();
	private static LongSupplier clock = System::nanoTime;
	private static long lastStats = Long.MIN_VALUE;
	private static String pendingStats;
	/** Waiting terrain cells, (cx, cz) -> {y, s}: a cell sent again keeps its newest value and moves to the back. */
	private static final LinkedHashMap<Long, int[]> terrain = new LinkedHashMap<>();
	private static long lastTerrain = Long.MIN_VALUE;
	private static long terrainDropped;
	private static boolean terrainAnnounced;

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

	/**
	 * One UP message from the DLL (link thread). {@code stats} is held to 1 Hz; {@code terrain} waits for the next batch
	 * ({@link #tick}); the rest is split to the cap and sent in order.
	 */
	public static synchronized void up(final String t, final JsonObject m, final String raw) {
		if (t.equals("terrain")) {
			bufferTerrain(m);
			return;
		}

		if (t.equals("stats")) {
			long now = clock.getAsLong();
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

	/** Client tick: the newest held-back {@code stats}, once its second has passed, and the next {@code terrain} batch when due. */
	public static synchronized void tick() {
		long now = clock.getAsLong();
		if (pendingStats != null && now - lastStats >= STATS_INTERVAL_NANOS) {
			lastStats = now;
			send("stats", pendingStats);
			pendingStats = null;
		}

		if (!terrain.isEmpty() && (lastTerrain == Long.MIN_VALUE || now - lastTerrain >= TERRAIN_INTERVAL_NANOS)) {
			lastTerrain = now;
			send("terrain", takeTerrainBatch());
		}
	}

	/** A DLL {@code terrain} into the waiting cells; one that breaks protocol.md's limits is dropped whole (the server would too). */
	private static void bufferTerrain(final JsonObject m) {
		int[] c = AtlasWire.parseTerrain(m);
		if (c == null) {
			int n = dropped.incrementAndGet();
			if (n % 100 == 1) {
				Cyberblock.LOG.warn("relay: dropped an invalid terrain ({} dropped so far)", n);
			}

			return;
		}

		for (int i = 0; i < c.length; i += 4) {
			terrain.putLast(AtlasTiles.key(c[i], c[i + 1]), new int[] {c[i + 2], c[i + 3]});
		}

		while (terrain.size() > TERRAIN_BUFFER) {
			terrain.pollFirstEntry();
			if (terrainDropped++ % 10_000 == 0) {
				Cyberblock.LOG.warn("relay: terrain is coming faster than it can go up: dropped the oldest waiting cells ({} so far)", terrainDropped);
			}
		}
	}

	/** The oldest waiting cells as one terrain message: at most TERRAIN_BATCH cells and within the cap, so it is never split. */
	private static String takeTerrainBatch() {
		JsonArray c = new JsonArray();
		int bytes = TERRAIN_SHELL_BYTES, cells = 0;
		for (var it = terrain.entrySet().iterator(); it.hasNext() && cells < TERRAIN_BATCH;) {
			var e = it.next();
			int cx = AtlasTiles.keyA(e.getKey()), cz = AtlasTiles.keyB(e.getKey()), y = e.getValue()[0], s = e.getValue()[1];
			int quad = digits(cx) + digits(cz) + digits(y) + digits(s) + 4; // the four numbers and their commas
			if (bytes + quad > cap && cells > 0) {
				break;
			}

			it.remove();
			c.add(cx);
			c.add(cz);
			c.add(y);
			c.add(s);
			bytes += quad;
			cells++;
		}

		if (!terrainAnnounced) {
			terrainAnnounced = true;
			Cyberblock.LOG.info("relay: terrain goes up to the server's shared map in batches (at most {} a second, {} cells each)",
				1_000_000_000L / TERRAIN_INTERVAL_NANOS, TERRAIN_BATCH);
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "terrain");
		m.add("c", c);
		return m.toString();
	}

	private static int digits(final int v) {
		return Integer.toString(v).length();
	}

	/** Terrain cells waiting for a batch (tests). */
	static synchronized int terrainWaiting() {
		return terrain.size();
	}

	/** Terrain cells dropped because the buffer was full (tests). */
	static synchronized long terrainDropped() {
		return terrainDropped;
	}

	/** Tests: a fresh relay on the given clock (nanoseconds). */
	static synchronized void reset(final LongSupplier testClock) {
		clock = testClock;
		lastStats = Long.MIN_VALUE;
		pendingStats = null;
		terrain.clear();
		lastTerrain = Long.MIN_VALUE;
		terrainDropped = 0;
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
	 * every chunk keeping the other fields. A {@code ground} message's {@code m} (Phase 3: one material code per quadruple) is cut
	 * with its quadruples; an {@code m} of the wrong length is left out (materials are optional: the server's heuristic palette).
	 * Empty when it cannot fit (any other type over the cap, or one unit alone too big).
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
		@Nullable JsonArray codes = null;
		if (t.equals("ground") && shell.has("m")) {
			codes = shell.get("m") instanceof JsonArray a && a.size() * unit == all.size() ? a : null;
			shell.remove("m");
			if (codes != null) {
				shell.add("m", new JsonArray());
			}
		}

		int base = bytes(shell.toString());
		List<String> out = new ArrayList<>();
		JsonArray chunk = new JsonArray();
		JsonArray chunkCodes = new JsonArray();
		int size = base;
		for (int i = 0; i < all.size(); i += unit) {
			int unitBytes = 0;
			for (int j = 0; j < unit; j++) {
				unitBytes += bytes(all.get(i + j).toString()) + 1; // the element and its comma
			}

			if (codes != null) {
				unitBytes += bytes(codes.get(i / unit).toString()) + 1;
			}

			if (base + unitBytes > cap) {
				return List.of();
			}

			if (size + unitBytes > cap && !chunk.isEmpty()) {
				out.add(with(shell, field, chunk, codes == null ? null : chunkCodes));
				chunk = new JsonArray();
				chunkCodes = new JsonArray();
				size = base;
			}

			for (int j = 0; j < unit; j++) {
				JsonElement e = all.get(i + j);
				chunk.add(e);
			}

			if (codes != null) {
				chunkCodes.add(codes.get(i / unit));
			}

			size += unitBytes;
		}

		if (!chunk.isEmpty()) {
			out.add(with(shell, field, chunk, codes == null ? null : chunkCodes));
		}

		return out;
	}

	private static String with(final JsonObject shell, final String field, final JsonArray chunk, final @Nullable JsonArray codes) {
		JsonObject copy = shell.deepCopy();
		copy.add(field, chunk);
		if (codes != null) {
			copy.add("m", codes);
		}

		return copy.toString();
	}

	static int bytes(final String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}
}
