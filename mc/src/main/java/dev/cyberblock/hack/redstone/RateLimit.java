package dev.cyberblock.hack.redstone;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Megaplan 6-R1: each redstone hacking block acts at most {@link #MAX} times in {@link #WINDOW_TICKS} (2 a second). Keyed by the
 * block's packed position. Pure; server thread only.
 */
final class RateLimit {
	static final int MAX = 2;
	static final long WINDOW_TICKS = 20;
	private final Map<Long, ArrayDeque<Long>> recent = new HashMap<>();

	/** May the block at this key act at tick now? When it may, the action is counted. */
	boolean allow(final long key, final long now) {
		ArrayDeque<Long> times = this.recent.computeIfAbsent(key, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_TICKS) {
			times.pollFirst();
		}

		if (times.size() >= MAX) {
			return false;
		}

		times.addLast(now);
		return true;
	}

	/** Drops keys with nothing in their window (called now and then, so removed blocks leave nothing behind). */
	void prune(final long now) {
		this.recent.values().removeIf(times -> times.isEmpty() || now - times.peekLast() >= WINDOW_TICKS);
	}

	void clear() {
		this.recent.clear();
	}

	int size() {
		return this.recent.size();
	}
}
