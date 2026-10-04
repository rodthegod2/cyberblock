package dev.cyberblock.npc;

import java.util.random.RandomGenerator;

/**
 * The ripperdoc's surgery minigame for the top implants (megaplan task 7-N1): a timing bar. A marker runs from 0 to 1 and back once
 * per period; the player cuts while it is inside the zone. The riskier the implant ({@code ripperdoc} content {@code risk}), the
 * narrower the zone and the faster the marker. The server times the cut itself (the time since it sent the bar, less the player's
 * ping), so a client cannot claim a better moment. Failure: health lost (never below one half heart: never death) and a 30 s
 * debuff. Pure.
 */
public final class Surgery {
	/** How long the player has to cut, and the debuff after a failure. */
	public static final int TIMEOUT_MS = 15_000, DEBUFF_TICKS = 600;
	/** A cut this close outside the zone still counts (rounding, one frame of drift). */
	static final double GRACE = 0.015;

	private Surgery() {
	}

	/** One sweep (0 → 1 → 0) in ms: 1800 for the safest surgery, 900 for the riskiest. */
	public static int period(final double risk) {
		return (int) Math.round(1800.0 - 900.0 * clamp(risk));
	}

	/** The zone's width: 0.30 of the bar for the safest surgery, 0.08 for the riskiest. */
	static double width(final double risk) {
		return 0.30 - 0.22 * clamp(risk);
	}

	/** The zone [lo, hi], placed at random inside 0.15..0.85. */
	public static double[] zone(final double risk, final RandomGenerator random) {
		double w = width(risk);
		double lo = 0.15 + random.nextDouble() * (0.70 - w);
		return new double[] {lo, lo + w};
	}

	/** Where the marker is after ms (a triangle wave starting at 0). */
	public static double position(final long ms, final int period) {
		double phase = Math.floorMod(Math.max(0L, ms), (long) period) / (double) period;
		return 1.0 - Math.abs(phase * 2.0 - 1.0);
	}

	/** The time on the player's bar when the server got the cut: since the bar went out, less one round trip (ping). */
	static long elapsed(final long sentMs, final long nowMs, final int pingMs) {
		return Math.max(0L, nowMs - sentMs - Math.max(0, Math.min(pingMs, 1000)));
	}

	public static boolean hit(final double position, final double lo, final double hi) {
		return position >= lo - GRACE && position <= hi + GRACE;
	}

	/** Health a failed surgery costs: 4 to 12 points by risk, never so much that the player drops below 1 (never death). */
	static float damage(final double risk, final float health) {
		return (float) Math.max(0.0, Math.min(4.0 + 8.0 * clamp(risk), health - 1.0));
	}

	private static double clamp(final double risk) {
		return Math.clamp(risk, 0.0, 1.0);
	}
}
