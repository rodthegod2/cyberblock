package dev.cyberblock.mode.overlord;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Overlord's fairness rules (megaplan 8-O1), pure: every spawn and event is announced 3 s ahead (a sound and a marker) and may
 * land only where it touches nobody but players in this match who opted in. A spawn also keeps the intensity's minimum distance from
 * every player and stays under its cap on simultaneous spawns; it must be inside the match area and near an opted-in player (so it is
 * for the match, never a mob dropped on the rest of the server).
 */
public final class Fairness {
	/** The warning before anything lands: 3 s. */
	public static final int WARNING_TICKS = 60;
	/** Every player this close to a spawn (blocks, horizontal) must be in the match and opted in. */
	public static final double NEAR = 32.0;
	/** A spawn or event must have an opted-in player of the match within this distance. */
	public static final double REACH = 64.0;
	/** The area around the match's players when the match names none (blocks on each side). */
	public static final double AREA_MARGIN = 48.0;

	/** A player near the point: eligible = in this match and opted in. */
	public record Who(String name, double x, double z, boolean eligible) {
		double distance(final double px, final double pz) {
			return Math.hypot(this.x - px, this.z - pz);
		}
	}

	/** The match area, a rectangle in blocks (x0 <= x1, z0 <= z1). */
	public record Area(double x0, double z0, double x1, double z1) {
		public boolean contains(final double x, final double z) {
			return x >= this.x0 && x <= this.x1 && z >= this.z0 && z <= this.z1;
		}

		/** The box around these players plus the margin; null when there are none. */
		public static @Nullable Area around(final List<Who> players, final double margin) {
			if (players.isEmpty()) {
				return null;
			}

			double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
			for (Who w : players) {
				x0 = Math.min(x0, w.x());
				z0 = Math.min(z0, w.z());
				x1 = Math.max(x1, w.x());
				z1 = Math.max(z1, w.z());
			}

			return new Area(x0 - margin, z0 - margin, x1 + margin, z1 + margin);
		}
	}

	/**
	 * One request. {@code units}: how many mobs it adds (0 for an event that spawns none); {@code live}: Overlord spawns of the match
	 * alive now (including those still warning); {@code near}: the radius inside which every player must be eligible (an event uses
	 * its own radius when larger); {@code spawn}: true for a spawn (the minimum distance applies), false for an event.
	 */
	public record Ask(double x, double z, int units, int live, Intensity intensity, List<Who> players, @Nullable Area area, double near, boolean spawn) {
	}

	/** Why a request is refused: a lang key (under {@code cyberblock.overlord.refused.}), its English and its arguments. */
	public record Refusal(String key, String english, Object... args) {
		public String text() {
			return String.format(Locale.ROOT, this.english, this.args);
		}
	}

	/** Supply-drop eddies (section 1.16 lists no Overlord income): at most this many per drop, per match in all, and to one player per match. */
	public static final int DROP_EDDIES = 100, MATCH_EDDIES = 300, PLAYER_EDDIES = 100;

	private Fairness() {
	}

	/**
	 * Each receiver's share of a drop's eddies: the drop's eddies (at most DROP_EDDIES and what the match may still pay) split evenly
	 * (the rest is not paid), each share at most what that player may still get. {@code paidTo}: what each receiver got this match.
	 */
	public static int[] eddieShares(final int eddies, final int matchPaid, final int[] paidTo) {
		int[] out = new int[paidTo.length];
		if (out.length == 0) {
			return out;
		}

		int pot = Math.max(0, Math.min(Math.min(eddies, DROP_EDDIES), MATCH_EDDIES - matchPaid));
		for (int i = 0; i < out.length; i++) {
			out[i] = Math.max(0, Math.min(pot / out.length, PLAYER_EDDIES - paidTo[i]));
		}

		return out;
	}

	/** Null when the request is fair, else the first rule it breaks. */
	public static @Nullable Refusal check(final Ask a) {
		if (!Double.isFinite(a.x()) || !Double.isFinite(a.z())) {
			return new Refusal("point", "Pick a point on the map");
		}

		if (a.spawn() && a.units() < 1) {
			return new Refusal("units", "Spawn at least one");
		}

		int cap = a.intensity().cap();
		if (a.units() > 0 && a.live() + a.units() > cap) {
			return new Refusal("cap", "Too many Overlord spawns at once: %s alive, the limit is %s", a.live(), cap);
		}

		if (a.area() == null || !a.area().contains(a.x(), a.z())) {
			return new Refusal("area", "Outside the match area");
		}

		if (a.spawn()) {
			double min = a.intensity().minDistance();
			for (Who w : a.players()) {
				if (w.distance(a.x(), a.z()) < min) {
					return new Refusal("too_close", "Too close to %s: keep %s blocks away", w.name(), (int) min);
				}
			}
		}

		double near = Math.max(NEAR, a.near());
		boolean target = false;
		for (Who w : a.players()) {
			double d = w.distance(a.x(), a.z());
			if (!w.eligible() && d < near) {
				return new Refusal("not_opted", "%s is near and is not an opted-in player of this match", w.name());
			}

			target |= w.eligible() && d <= REACH;
		}

		return target ? null : new Refusal("no_target", "No opted-in player of this match within %s blocks", (int) REACH);
	}
}
