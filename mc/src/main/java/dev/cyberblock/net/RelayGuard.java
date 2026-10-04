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
 *
 * <p>The server's copy of the host's player can lag its client: when Cyberblock turns on, the DLL sends {@code clear} and the whole
 * ground around V in the same client tick that moves Steve to V, before his movement packet. So a ground or unground with far
 * entries is held (in order, with every message after it) for up to {@link #HOLD_MS} and checked again each server tick
 * ({@link #drain}) against the newer position; only what is still far after that is dropped as far.
 */
final class RelayGuard {
	static final String KICK = "Cyberblock: too many messages from your Cyberpunk link";
	static final double CELLS_PER_SECOND = 20_000.0;
	static final int GUEST_KICK = 50;
	static final long GUEST_WINDOW_MS = 60_000L;
	static final double OVERFLOW_FACTOR = 5.0;
	static final int OVERFLOW_SECONDS = 10;
	static final long LOG_MS = 5_000L;
	/** How long a far ground or unground waits for the host's player position to catch up. */
	static final long HOLD_MS = 2_000L;
	/** Messages held at most; past it the oldest is checked as it stands (the buckets keep the queue far below this). */
	static final int HOLD_CAP = 256;

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
		// Phase 6 (package 6I, section 1.9 row 6): the photo feed, at most one a second while a feedreq is on
		Map.entry("feed", new Rule(2, 4, Sanity.Kind.NONE, false)),
		// Phase 3 (package 3E): env at the DLL's 1 Hz like stats; vteleported answers a vteleport request id (its p is checked by
		// MeetUp against the requested spot: V's new place is far from Steve by design)
		Map.entry("env", new Rule(2, 4, Sanity.Kind.NONE, false)),
		Map.entry("vteleported", new Rule(2, 4, Sanity.Kind.NONE, true)),
		// Phase 4 (package 4M, section 1.6): the device list (its entries' p within the radius), state changes, devact answers
		Map.entry("devices", new Rule(10, 40, Sanity.Kind.LIST, false)),
		Map.entry("devstate", new Rule(10, 40, Sanity.Kind.NONE, false)),
		Map.entry("devresult", new Rule(10, 40, Sanity.Kind.NONE, true)),
		// Phase 7 (task 7-H1, the hub): parked answers each park on/off, a few per hub visit (Phase 3's descopes note: Phase 7 adds it)
		Map.entry("parked", new Rule(2, 4, Sanity.Kind.NONE, false)),
		// LOCAL until Phase 3 (the shared Night City map); its row is ready: 30/s, the survey's burst of 200
		Map.entry("terrain", new Rule(30, 200, Sanity.Kind.TERRAIN, false))));

	/** A later phase adds its UP type (Phase 4: devices, devstate, devresult at 10 (40)). Before any player joins. */
	static void register(final String t, final double rate, final double burst, final Sanity.Kind positions, final boolean reply) {
		RULES.put(t, new Rule(rate, burst, positions, reply));
	}

	static boolean isUpType(final String t) {
		return RULES.containsKey(t);
	}

	/** A checked (possibly filtered) message for the handlers. */
	record Message(String t, JsonObject m) {
	}

	/** messages: what is ready for the handlers, oldest first (empty when dropped or held). kick: the disconnect text, or null. */
	record Verdict(List<Message> messages, @Nullable String kick) {
		static final Verdict DROP = new Verdict(List.of(), null);

		/** The newest ready message, or null. */
		@Nullable JsonObject message() {
			return this.messages.isEmpty() ? null : this.messages.getLast().m();
		}
	}

	/** A message that passed the envelope checks and waits for its position checks (until: ms). */
	private record Held(String t, Rule rule, JsonObject m, long until) {
	}

	private final double rateMultiplier;
	private final double radius;
	private final LongSupplier clock;
	private final Map<String, TokenBucket> buckets = new HashMap<>();
	private final TokenBucket cells;
	private final Map<String, Window> windows = new HashMap<>();
	private final ArrayDeque<Long> guestDrops = new ArrayDeque<>();
	private final ArrayDeque<Held> held = new ArrayDeque<>();
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
	 * @return the messages now ready (this one, unless it or an older one is held) or the kick
	 */
	Verdict check(final boolean host, final String t, final String json, final double px, final double pz, final IntPredicate issued) {
		long now = this.clock.getAsLong();
		if (!host) {
			this.held.clear();
			this.count(t, "guest", 1);
			this.guestDrops.addLast(now);
			while (!this.guestDrops.isEmpty() && now - this.guestDrops.peekFirst() >= GUEST_WINDOW_MS) {
				this.guestDrops.pollFirst();
			}

			return this.guestDrops.size() >= GUEST_KICK ? new Verdict(List.of(), KICK) : Verdict.DROP;
		}

		Rule rule = RULES.get(t);
		if (rule == null) {
			this.count(t, "type", 1);
			return Verdict.DROP;
		}

		if (this.overflowing(t, rule, now)) {
			return new Verdict(List.of(), KICK);
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

		this.held.addLast(new Held(t, rule, m, now + HOLD_MS));
		return new Verdict(this.drain(true, px, pz, issued), null);
	}

	/**
	 * Every server tick and after every check: the held messages that are ready, oldest first, through the position and request-id
	 * checks. A ground or unground at the head with far entries waits until its time is up; nothing after it overtakes it.
	 *
	 * @param host the sender still holds the host role (otherwise everything held is discarded)
	 */
	List<Message> drain(final boolean host, final double px, final double pz, final IntPredicate issued) {
		if (this.held.isEmpty()) {
			return List.of();
		}

		if (!host) {
			this.held.clear();
			return List.of();
		}

		long now = this.clock.getAsLong();
		List<Message> out = new ArrayList<>(1);
		while (!this.held.isEmpty()) {
			Held h = this.held.peekFirst();
			if (h.rule().positions() == Sanity.Kind.RANGES && now < h.until() && this.held.size() <= HOLD_CAP && this.anyFar(h.m(), px, pz)) {
				break;
			}

			this.held.pollFirst();
			JsonObject m = this.positions(h.t(), h.rule(), h.m(), px, pz, issued);
			if (m != null) {
				out.add(new Message(h.t(), m));
			}
		}

		return out;
	}

	/** Whether a ranges message has entries outside the radius (false when malformed: positions() drops it as invalid). */
	private boolean anyFar(final JsonObject m, final double px, final double pz) {
		try {
			return Sanity.filter(Sanity.Kind.RANGES, m.deepCopy(), px, pz, this.radius).far() > 0;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** The sanity radius, the cell budget and the request id: the checked message, or null when dropped (and counted). */
	private @Nullable JsonObject positions(final String t, final Rule rule, final JsonObject m, final double px, final double pz,
		final IntPredicate issued) {
		Sanity.Result near;
		try {
			near = Sanity.filter(rule.positions(), m, px, pz, this.radius);
		} catch (IllegalArgumentException e) {
			this.count(t, "invalid", 1);
			return null;
		}

		if (near.far() > 0) {
			this.count(t, "far", near.far());
		}

		if (!near.keep()) {
			return null;
		}

		if (rule.positions() == Sanity.Kind.RANGES) {
			long n = cellsOf(m);
			if (!this.cells.tryTake(n)) {
				this.count(t, "cells", 1);
				return null;
			}
		}

		if (rule.reply()) {
			JsonElement id = m.get("id");
			boolean ok = id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsDouble() == Math.rint(id.getAsDouble())
				&& Math.abs(id.getAsDouble()) <= Integer.MAX_VALUE && issued.test(id.getAsInt());
			if (!ok) {
				this.count(t, "id", 1);
				return null;
			}
		}

		return m;
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
