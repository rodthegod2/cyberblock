package dev.cyberblock.cyber;

/**
 * When a held hack key sends a `scan` (task C4): when the picked target changes, or every second for the same one, and
 * never more than 5 per second (the protocol's limit). Not thread-safe: the client thread owns it.
 */
public final class ScanThrottle {
	public static final long REPEAT_MS = 1000L;
	public static final long MIN_GAP_MS = 200L;
	private long lastTarget;
	private long lastSentMs;
	private boolean sentAny;

	/** Call for every pick; true means send now (and the send is recorded). target: anything comparable, -1 for none. */
	public boolean shouldSend(final long nowMs, final long target) {
		if (this.sentAny) {
			long since = nowMs - this.lastSentMs;
			if (since < MIN_GAP_MS || target == this.lastTarget && since < REPEAT_MS) {
				return false;
			}
		}

		this.sentAny = true;
		this.lastTarget = target;
		this.lastSentMs = nowMs;
		return true;
	}

	/** The key was released: the next hold sends at once. */
	public void reset() {
		this.sentAny = false;
	}
}
