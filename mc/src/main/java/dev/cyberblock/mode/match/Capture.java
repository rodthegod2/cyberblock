package dev.cyberblock.mode.match;

/**
 * Gang Wars capture points (megaplan 8-M1: "stand in a zone to capture"), pure and tested. A point has an owner (a team, or -1)
 * and a progress bar 0..1 that belongs to one team (its holder). Each step:
 * <ul>
 * <li>exactly one team stands in the circle: if the bar is its own (or empty) the bar fills, and when full the team owns the point;
 * if the bar is another team's it drains first, and when empty the point is neutral again and the bar becomes the new team's;</li>
 * <li>two or more teams stand in it (contested): nothing moves;</li>
 * <li>nobody: on a neutral point an unfinished bar drains back at half speed; an owned point keeps its bar.</li>
 * </ul>
 * More players capture faster, up to {@link #MAX_RATE_PLAYERS}.
 */
public final class Capture {
	public static final int MAX_RATE_PLAYERS = 3;
	/** Sums of tenths do not add up to exactly 1: a bar this close is full (or empty). */
	private static final double EPS = 1e-9;

	/** What a step changed. */
	public enum Change {
		NONE, NEUTRALISED, CAPTURED
	}

	/** One capture point (server thread). Position and radius are fixed; owner, holder and progress move. */
	public static final class Point {
		public final String name;
		public final double x, y, z, r;
		int owner = -1;
		int holder = -1;
		double progress;

		public Point(final String name, final double x, final double y, final double z, final double r) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
			this.r = r;
		}

		public int owner() {
			return this.owner;
		}

		public int holder() {
			return this.holder;
		}

		public double progress() {
			return this.progress;
		}

		/** Horizontally inside the circle (city heights vary too much for a vertical limit). */
		public boolean inside(final double px, final double pz) {
			double dx = px - this.x, dz = pz - this.z;
			return dx * dx + dz * dz <= this.r * this.r;
		}
	}

	private Capture() {
	}

	/**
	 * One step of {@code dt} seconds.
	 *
	 * @param counts players of each team standing in the circle (index = team)
	 * @param captureSeconds seconds one player needs to fill an empty bar
	 */
	public static Change step(final Point p, final int[] counts, final double dt, final double captureSeconds) {
		int present = -1;
		int teams = 0;
		for (int t = 0; t < counts.length; t++) {
			if (counts[t] > 0) {
				present = t;
				teams++;
			}
		}

		double rate = dt / Math.max(1.0, captureSeconds);
		if (teams == 0) {
			// the holder is always the owner on an owned point (another team must neutralise it first), so only a neutral
			// point's unfinished bar fades; an owned point keeps whatever an attacker left of its bar
			if (p.owner < 0) {
				p.progress = p.progress - rate / 2.0 <= EPS ? 0.0 : p.progress - rate / 2.0;
				if (p.progress == 0.0) {
					p.holder = -1;
				}
			}

			return Change.NONE;
		}

		if (teams > 1) {
			return Change.NONE;
		}

		rate *= Math.min(counts[present], MAX_RATE_PLAYERS);
		if (p.holder == present || p.holder < 0) {
			p.holder = present;
			p.progress = p.progress + rate >= 1.0 - EPS ? 1.0 : p.progress + rate;
			if (p.progress >= 1.0 && p.owner != present) {
				p.owner = present;
				return Change.CAPTURED;
			}

			return Change.NONE;
		}

		p.progress -= rate;
		if (p.progress > EPS) {
			return Change.NONE;
		}

		p.progress = 0.0;
		p.holder = present;
		if (p.owner >= 0 && p.owner != present) {
			p.owner = -1;
			return Change.NEUTRALISED;
		}

		return Change.NONE;
	}
}
