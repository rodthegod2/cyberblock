package dev.cyberblock.hack.actions;

import java.util.HashMap;
import java.util.Map;

/** Wall-clock cooldowns by key (a player's hack, an owner's blackout). Pure (the clock is passed in). */
final class Cooldowns {
	private final Map<String, Long> until = new HashMap<>();

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

	void clear() {
		this.until.clear();
	}
}
