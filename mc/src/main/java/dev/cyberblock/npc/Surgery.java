package dev.cyberblock.npc;

/**
 * The ripperdoc's surgery minigame for the top implants (megaplan task 7-N1; 7A's {@code ripperdoc} catalogue): a timing bar. A marker
 * runs the bar from 0 to 1 and back once per {@code period}; the player cuts while it is inside the centre {@code window} (percent of
 * the bar). The server times the cut itself (the time since it sent the bar, less the player's ping), so a client cannot claim a
 * better moment. Failure: the catalogue's damage (never below half a heart: never death) and a debuff. Pure.
 */
public final class Surgery {
	/** How long the player has to cut. */
	public static final int TIMEOUT_MS = 15_000;
	/** A cut this close outside the zone still counts (rounding, one frame of drift). */
	static final double GRACE = 0.015;
	/** The least health a failed surgery leaves: half a heart. */
	static final float FLOOR = 1.0F;

	private Surgery() {
	}

	/** The centre zone [lo, hi] for a window of this many percent of the bar. */
	public static double[] zone(final int windowPercent) {
		double half = Math.clamp(windowPercent, 1, 100) / 200.0;
		return new double[] {0.5 - half, 0.5 + half};
	}

	/** One sweep there and back, in ms, from the catalogue's ticks. */
	public static int periodMs(final int ticks) {
		return Math.clamp(ticks, 4, 200) * 50;
	}

	/** Where the marker is after ms (a triangle wave starting at 0). */
	public static double position(final long ms, final int period) {
		double phase = Math.floorMod(Math.max(0L, ms), (long) period) / (double) period;
		return 1.0 - Math.abs(phase * 2.0 - 1.0);
	}

	/** The time on the player's bar when the server got the cut: since the bar went out, less one round trip (ping, at most 1 s). */
	static long elapsed(final long sentMs, final long nowMs, final int pingMs) {
		return Math.max(0L, nowMs - sentMs - Math.max(0, Math.min(pingMs, 1000)));
	}

	public static boolean hit(final double position, final double lo, final double hi) {
		return position >= lo - GRACE && position <= hi + GRACE;
	}

	/** Health a failed surgery takes: the catalogue's damage (half-hearts), never so much that the player drops below half a heart. */
	static float damage(final int halfHearts, final float health) {
		return Math.max(0.0F, Math.min(halfHearts, health - FLOOR));
	}
}
