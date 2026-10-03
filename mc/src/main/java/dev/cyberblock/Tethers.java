package dev.cyberblock;

/** Layer 5 numbers (plan 2026-10-03 M5): how long a lead is and how hard a fishing rod or a wind charge pulls and pushes. */
public final class Tethers {
	/** Lead length (m): the host keeps the NPC or vehicle within it. */
	static final double LEAD_HUMAN = 5.0, LEAD_VEHICLE = 8.0;
	/** Reeling in a hooked NPC or vehicle (m/s toward Steve's eyes). */
	static final double PULL_HUMAN = 9.0, PULL_VEHICLE = 7.0;
	/** A wind burst pushes Night City at this many m/s per block of explosion radius. */
	static final double KNOCK_PER_POWER = 12.0;
	/** The protocol's limits for knock. */
	static final double MAX_KNOCK_RADIUS = 16.0, MAX_KNOCK_STRENGTH = 50.0;

	private Tethers() {
	}

	public static double leadLength(final boolean vehicle) {
		return vehicle ? LEAD_VEHICLE : LEAD_HUMAN;
	}

	public static double pullStrength(final boolean vehicle) {
		return vehicle ? PULL_VEHICLE : PULL_HUMAN;
	}

	/** The push of a wind burst of this radius (m/s), within the protocol's 0..50. */
	public static double knockStrength(final double power) {
		return Math.clamp(KNOCK_PER_POWER * power, 0.0, MAX_KNOCK_STRENGTH);
	}
}
