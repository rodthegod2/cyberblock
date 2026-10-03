package dev.cyberblock.cyber;

import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** Server side of scanning and hacking: the locked target (task C4) and request ids. Server thread unless noted. */
public final class Hacking {
	/** A scanned target stays locked this long. */
	static final long LOCK_NANOS = 5_000_000_000L;
	private static final AtomicInteger ids = new AtomicInteger();
	private static @Nullable Locked locked;

	/** What the last successful scan found: a Cyberpunk id, and whether it is a device. */
	public record Locked(long target, boolean device, long untilNanos) {
	}

	private Hacking() {
	}

	/** A fresh request id for `scan` or `hack` (int >= 0, any thread). */
	public static int nextId() {
		return ids.getAndUpdate(i -> i == Integer.MAX_VALUE ? 0 : i + 1);
	}

	/** A `scaninfo` with ok: this target is the one quickhacks go to for the next 5 s. */
	public static void lock(final long target, final boolean device) {
		locked = new Locked(target, device, System.nanoTime() + LOCK_NANOS);
	}

	/** The locked target, or null when none was scanned in the last 5 s. Wall time: a Sandevistan slowing the ticks does not stretch it. */
	public static @Nullable Locked locked() {
		Locked l = locked;
		return l != null && System.nanoTime() - l.untilNanos() < 0 ? l : null;
	}

	/** Server stopping: forget everything. */
	static void clear() {
		locked = null;
	}
}
