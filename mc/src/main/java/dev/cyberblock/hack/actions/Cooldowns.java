package dev.cyberblock.hack.actions;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Wall-clock cooldowns by key (a player's hack, an owner's blackout, a player's acts this second). Pure (the clock is passed in). */
final class Cooldowns {
	private final Map<String, Long> until = new HashMap<>();
	/** key -> the times (ms) of its uses in the last second, for {@link #withinRate}. */
	private final Map<String, ArrayDeque<Long>> recent = new HashMap<>();

	/** Whole seconds left, 0 when ready (rounded up). */
	long secondsLeft(final String key, final long nowMs) {
		Long end = this.until.get(key);
		if (end == null || end <= nowMs) {
			this.until.remove(key);
			return 0L;
		}

		return (end - nowMs + 999L) / 1000L;
	}

	void start(final String key, final long nowMs, final long seconds) {
		if (seconds > 0) {
			this.until.put(key, nowMs + seconds * 1000L);
		}
	}

	/** Ends one now (e.g. a blackout lock whose blackout Night City refused or never answered). */
	void cancel(final String key) {
		this.until.remove(key);
	}

	/** At most `max` per second for this key: true (and counted) when one more fits in the last second. */
	boolean withinRate(final String key, final long nowMs, final int max) {
		ArrayDeque<Long> times = this.recent.computeIfAbsent(key, k -> new ArrayDeque<>());
		while (!times.isEmpty() && nowMs - times.peekFirst() >= 1000L) {
			times.pollFirst();
		}

		if (times.size() >= max) {
			return false;
		}

		times.addLast(nowMs);
		return true;
	}

	void clear() {
		this.until.clear();
		this.recent.clear();
	}
}
