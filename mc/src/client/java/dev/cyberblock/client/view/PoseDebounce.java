package dev.cyberblock.client.view;

/**
 * Q-V2: one render flag of third-person Steve (using an item, blocking) that only changes once the raw value has held the new
 * value for {@link #HOLD_TICKS} client ticks, so a one-tick blip in the synced state never flashes another pose.
 */
public final class PoseDebounce {
	public static final int HOLD_TICKS = 2;

	private boolean shown;
	private boolean pending;
	private int since;

	/** The value to draw this frame, given the raw value and the client tick it was read on. */
	public boolean update(final boolean raw, final int tick) {
		if (raw == shown) {
			pending = false;
			return shown;
		}

		if (!pending) {
			pending = true;
			since = tick;
		}

		if (tick - since >= HOLD_TICKS) {
			shown = raw;
			pending = false;
		}

		return shown;
	}

	/** Take this value at once (a real change, or no host: nothing to debounce against). */
	public void force(final boolean value) {
		shown = value;
		pending = false;
	}
}
