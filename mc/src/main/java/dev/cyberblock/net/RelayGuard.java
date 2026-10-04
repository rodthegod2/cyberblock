package dev.cyberblock.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The envelope checks of megaplan section 1.6 for one connection, run on every {@code cyberblock:up} before any handler: only a
 * host may send up (a guest's are dropped, 50 in a minute kick it); the type must be an UP type of section 1.2; the json parses and
 * its t equals the envelope's; the size is within the cap; the per-type token bucket (scaled by the config's rate) has a token
 * (ground, unground and clear also share a 20000 cells/s budget); positions lie within the sanity radius; replies carry a request id
 * the session issued. Failures are dropped and counted, and the counters are logged every 5 s. A connection that keeps sending at
 * five times a type's rate for 10 s is kicked. Bad messages never end a session otherwise. Server thread; the clock is in ms.
 */
final class RelayGuard {
	static final String KICK = "Cyberblock: too many messages from your Cyberpunk link";
	static final double CELLS_PER_SECOND = 20_000.0;
	static final int GUEST_KICK = 50;
	static final long GUEST_WINDOW_MS = 60_000L;
	static final double OVERFLOW_FACTOR = 5.0;
	static final int OVERFLOW_SECONDS = 10;
	static final long LOG_MS = 5_000L;

	/** One UP type: its bucket (tokens per second, burst), where its positions are, and whether it is a reply with a request id. */
	record Rule(double rate, double burst, Sanity.Kind positions, boolean reply) {
	}

	private static final Map<String, Rule> RULES = new ConcurrentHashMap<>(Map.ofEntries(
		Map.entry("hello", new Rule(1, 3, Sanity.Kind.NONE, false)),
		Map.entry("ground", new Rule(40, 120, Sanity.Kind.RANGES, false)),
		Map.entry("unground", new Rule(40, 120, Sanity.Kind.RANGES, false)),
		Map.entry("clear", new Rule(40, 120, Sanity.Kind.NONE, false)),
		Map.entry("state", new Rule(10, 20, Sanity.Kind.NONE, false)),
		Map.entry("hstate", new Rule(10, 20, Sanity.Kind.NONE, false)),
		Map.entry("aim", new Rule(15, 30, Sanity.Kind.NONE, false)),
		Map.entry("dmg", new Rule(20, 40, Sanity.Kind.FROM, false)),
		Map.entry("npcs", new Rule(15, 30, Sanity.Kind.LIST, false)),
		Map.entry("mobdmg", new Rule(30, 60, Sanity.Kind.NONE, false)),
		Map.entry("pcells", new Rule(30, 60, Sanity.Kind.CELLS, false)),
		Map.entry("unlead", new Rule(10, 20, Sanity.Kind.NONE, false)),
		Map.entry("killed", new Rule(10, 20, Sanity.Kind.NONE, false)),
		Map.entry("scaninfo", new Rule(40, 80, Sanity.Kind.POINT, true)),
		Map.entry("hackresult", new Rule(40, 80, Sanity.Kind.NONE, true)),
		Map.entry("hackspread", new Rule(40, 80, Sanity.Kind.NONE, true)),
		Map.entry("stats", new Rule(2, 4, Sanity.Kind.NONE, false)),
		// LOCAL until Phase 3 (the shared Night City map); its row is ready: 30/s, the survey's burst of 200
		Map.entry("terrain", new Rule(30, 200, Sanity.Kind.TERRAIN, false))));

	/** A later phase adds its UP type (Phase 4: devices, devstate, devresult at 10 (40)). Before any player joins. */
	static void register(final String t, final double rate, final double burst, final Sanity.Kind positions, final boolean reply) {
		RULES.put(t, new Rule(rate, burst, positions, reply));
	}

	static boolean isUpType(final String t) {
		return RULES.containsKey(t);
	}

	/** message: the checked (possibly filtered) message, or null when dropped. kick: the disconnect text, or null. */
	record Verdict(@Nullable JsonObject message, @Nullable String kick) {
		static final Verdict DROP = new Verdict(null, null);
	}

	private final double rateMultiplier;
	private final double radius;
	private final LongSupplier clock;
	private final Map<String, TokenBucket> buckets = new HashMap<>();
	private final TokenBucket cells;
	private final Map<String, Window> windows = new HashMap<>();
	private final ArrayDeque<Long> guestDrops = new ArrayDeque<>();
	/** t -> why -> count since the last log line. */
	private final Map<String, Map<String, Integer>> dropped = new LinkedHashMap<>();
	private long lastLog;

	/** Messages of one type in the current second, and how many whole seconds in a row were over five times the rate. */
	private static final class Window {
		long start;
		int count;
		int overSeconds;
	}

	RelayGuard(final double rateMultiplier, final double radius, final LongSupplier clock) {
		this.rateMultiplier = rateMultiplier;
		this.radius = radius;
		this.clock = clock;
		this.cells = new TokenBucket(CELLS_PER_SECOND * rateMultiplier, CELLS_PER_SECOND * rateMultiplier, clock);
		this.lastLog = clock.getAsLong();
	}

	/** Counts a drop (also used by the caller for a message its handler found invalid). */
	void count(final String t, final String why, final int n) {
		this.dropped.computeIfAbsent(t, k -> new LinkedHashMap<>()).merge(why, n, Integer::sum);
	}

	/**
	 * @param host the sender holds the host role (a session in any state)
	 * @param px the host's player position (x, z) for the sanity radius
	 * @param issued request ids the sender's session issued
	 */
	Verdict check(final boolean host, final String t, final String json, final double px, final double pz, final IntPredicate issued) {
		long now = this.clock.getAsLong();
		if (!host) {
			this.count(t, "guest", 1);
			this.guestDrops.addLast(now);
			while (!this.guestDrops.isEmpty() && now - this.guestDrops.peekFirst() >= GUEST_WINDOW_MS) {
				this.guestDrops.pollFirst();
			}

			return this.guestDrops.size() >= GUEST_KICK ? new Verdict(null, KICK) : Verdict.DROP;
		}

		Rule rule = RULES.get(t);
		if (rule == null) {
			this.count(t, "type", 1);
			return Verdict.DROP;
		}

		if (this.overflowing(t, rule, now)) {
			return new Verdict(null, KICK);
		}

		if (!Payloads.fits(json, Payloads.UP_CAP)) {
			this.count(t, "size", 1);
			return Verdict.DROP;
		}

		TokenBucket bucket = this.buckets.computeIfAbsent(t, k -> new TokenBucket(rule.rate() * this.rateMultiplier, rule.burst() * this.rateMultiplier, this.clock));
		if (!bucket.tryTake(1.0)) {
			this.count(t, "rate", 1);
			return Verdict.DROP;
		}

		JsonObject m = ServerNet.parse(json);
		JsonElement inner = m == null ? null : m.get("t");
		if (inner == null || !inner.isJsonPrimitive() || !t.equals(inner.getAsString())) {
			this.count(t, m == null ? "parse" : "t", 1);
			return Verdict.DROP;
		}

		Sanity.Result near;
		try {
			near = Sanity.filter(rule.positions(), m, px, pz, this.radius);
		} catch (IllegalArgumentException e) {
			this.count(t, "invalid", 1);
			return Verdict.DROP;
		}

		if (near.far() > 0) {
			this.count(t, "far", near.far());
		}

		if (!near.keep()) {
			return Verdict.DROP;
		}

		if (rule.positions() == Sanity.Kind.RANGES) {
			long n = cellsOf(m);
			if (!this.cells.tryTake(n)) {
				this.count(t, "cells", 1);
				return Verdict.DROP;
			}
		}

		if (rule.reply()) {
			JsonElement id = m.get("id");
			boolean ok = id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsDouble() == Math.rint(id.getAsDouble())
				&& Math.abs(id.getAsDouble()) <= Integer.MAX_VALUE && issued.test(id.getAsInt());
			if (!ok) {
				this.count(t, "id", 1);
				return Verdict.DROP;
			}
		}

		return new Verdict(m, null);
	}

	/** Block cells of a ranges message (already validated by Sanity): the sum of the column heights. */
	private static long cellsOf(final JsonObject m) {
		var c = m.getAsJsonArray("c");
		long n = 0;
		for (int i = 0; i + 3 < c.size(); i += 4) {
			n += (long) c.get(i + 3).getAsInt() - c.get(i + 2).getAsInt() + 1L;
		}

		return n;
	}

	/** Counts this message in its type's current second; true when the type was over five times its rate for 10 s in a row. */
	private boolean overflowing(final String t, final Rule rule, final long now) {
		Window w = this.windows.computeIfAbsent(t, k -> {
			Window fresh = new Window();
			fresh.start = now;
			return fresh;
		});
		if (now - w.start >= 1000L) {
			long seconds = (now - w.start) / 1000L;
			boolean over = w.count > OVERFLOW_FACTOR * rule.rate() * this.rateMultiplier;
			// an idle second between two busy ones breaks the run
			w.overSeconds = over && seconds == 1 ? w.overSeconds + 1 : 0;
			w.start += seconds * 1000L;
			w.count = 0;
		}

		w.count++;
		return w.overSeconds >= OVERFLOW_SECONDS;
	}

	/** Every 5 s: one line per type and reason with drops ("relay: <name> dropped <t> x N (<why>)"), then the counters restart. */
	List<String> drainLog(final String name) {
		long now = this.clock.getAsLong();
		if (now - this.lastLog < LOG_MS) {
			return List.of();
		}

		this.lastLog = now;
		List<String> lines = new ArrayList<>();
		// t is the sender's text (an unknown type too): keep the log line one plain line
		this.dropped.forEach((t, whys) -> whys.forEach((why, n) -> lines.add(String.format(Locale.ROOT, "relay: %s dropped %s x %d (%s)", name,
			t.replaceAll("[^A-Za-z0-9_]", "?"), n, why))));
		this.dropped.clear();
		return lines;
	}
}
