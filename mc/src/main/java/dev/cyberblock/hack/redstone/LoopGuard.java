package dev.cyberblock.hack.redstone;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Megaplan 6-R1 loop detection: a link chain that toggles the same device more than {@link #LIMIT} times in {@link #WINDOW_TICKS}
 * (4 s) is frozen for {@link #FREEZE_TICKS} (30 s): every relay linked to that device does nothing until then. Keyed by
 * {@link Link#key()}. Pure; server thread only.
 */
final class LoopGuard {
	static final int LIMIT = 8;
	static final long WINDOW_TICKS = 80;
	static final long FREEZE_TICKS = 600;

	enum Verdict {
		/** Counted; the toggle goes. */
		OK,
		/** This toggle was one too many: the device is frozen from now (the caller shows smoke and logs the loop). */
		TRIPPED,
		/** Already frozen: nothing goes. */
		FROZEN
	}

	private final Map<String, ArrayDeque<Long>> toggles = new HashMap<>();
	private final Map<String, Long> frozenUntil = new HashMap<>();

	/** A relay wants to toggle the device at tick now. */
	Verdict toggle(final String device, final long now) {
		if (this.frozen(device, now)) {
			return Verdict.FROZEN;
		}

		ArrayDeque<Long> times = this.toggles.computeIfAbsent(device, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_TICKS) {
			times.pollFirst();
		}

		if (times.size() >= LIMIT) {
			times.clear();
			this.frozenUntil.put(device, now + FREEZE_TICKS);
			return Verdict.TRIPPED;
		}

		times.addLast(now);
		return Verdict.OK;
	}

	boolean frozen(final String device, final long now) {
		Long until = this.frozenUntil.get(device);
		if (until == null) {
			return false;
		}

		if (now >= until) {
			this.frozenUntil.remove(device);
			return false;
		}

		return true;
	}

	/** Forgets devices with nothing recent and freezes that ran out. */
	void prune(final long now) {
		this.frozenUntil.values().removeIf(until -> now >= until);
		this.toggles.values().removeIf(times -> times.isEmpty() || now - times.peekLast() >= WINDOW_TICKS);
	}

	void clear() {
		this.toggles.clear();
		this.frozenUntil.clear();
	}
}
