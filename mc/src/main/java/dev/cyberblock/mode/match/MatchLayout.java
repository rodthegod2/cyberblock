package dev.cyberblock.mode.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a match puts its team spawns and Gang Wars capture points (megaplan 8-M1), pure and tested. Landmarks inside the zone
 * (the {@code landmark} content: the district's real places) are used first: each team's spawn is the spawn landmark closest to
 * its ideal spot on the zone's rim (teams spread evenly around it), capture points are the remaining landmarks nearest the centre.
 * Where the zone has too few landmarks the ideal spots are used (their height is the nearest landmark's, else the caller's
 * {@code y}).
 */
public final class MatchLayout {
	/** Capture point radius (blocks). */
	public static final double POINT_RADIUS = 6.0;
	/** Team spawns sit this far out (fraction of the zone radius); generated capture points at {@link #POINT_OUT}. */
	static final double BASE_OUT = 0.8, POINT_OUT = 0.4;

	/** A landmark: its name, position and whether it may be a spawn. */
	public record Spot(String name, double x, double y, double z, boolean spawn) {
	}

	public record Layout(List<Match.Base> bases, List<Capture.Point> points) {
	}

	private MatchLayout() {
	}

	public static Layout plan(final Match.Zone zone, final List<MatchMode.TeamSpec> teams, final int points, final List<Spot> landmarks, final double y) {
		List<Spot> free = new ArrayList<>(landmarks.stream().filter(s -> zone.inside(s.x(), s.z())).toList());
		List<Match.Base> bases = new ArrayList<>();
		int n = teams.size();
		for (int t = 0; t < n; t++) {
			double a = Math.PI * 2.0 * t / n;
			double ix = zone.x() + Math.cos(a) * zone.r() * BASE_OUT, iz = zone.z() + Math.sin(a) * zone.r() * BASE_OUT;
			Spot best = free.stream().filter(Spot::spawn).min(Comparator.comparingDouble(s -> dist2(s.x(), s.z(), ix, iz))).orElse(null);
			// a landmark more than half the radius away from the ideal spot would put two teams together: use the ideal spot
			if (best != null && dist2(best.x(), best.z(), ix, iz) <= zone.r() * zone.r() / 4.0) {
				free.remove(best);
				bases.add(new Match.Base(best.name(), best.x(), best.y(), best.z(), t));
			} else {
				bases.add(new Match.Base(teams.get(t).name(), ix, height(landmarks, ix, iz, y), iz, t));
			}
		}

		List<Capture.Point> out = new ArrayList<>();
		free.sort(Comparator.comparingDouble(s -> dist2(s.x(), s.z(), zone.x(), zone.z())));
		for (Spot s : free) {
			if (out.size() == points) {
				break;
			}

			out.add(new Capture.Point(s.name(), s.x(), s.y(), s.z(), POINT_RADIUS));
		}

		for (int i = out.size(); i < points; i++) {
			double a = Math.PI * 2.0 * i / points + Math.PI / Math.max(1, n);
			double px = zone.x() + Math.cos(a) * zone.r() * POINT_OUT, pz = zone.z() + Math.sin(a) * zone.r() * POINT_OUT;
			out.add(new Capture.Point("Point " + (char) ('A' + i), px, height(landmarks, px, pz, y), pz, POINT_RADIUS));
		}

		return new Layout(List.copyOf(bases), List.copyOf(out));
	}

	private static double height(final List<Spot> landmarks, final double x, final double z, final double fallback) {
		return landmarks.stream().min(Comparator.comparingDouble(s -> dist2(s.x(), s.z(), x, z))).map(Spot::y).orElse(fallback);
	}

	private static double dist2(final double ax, final double az, final double bx, final double bz) {
		double dx = ax - bx, dz = az - bz;
		return dx * dx + dz * dz;
	}
}
