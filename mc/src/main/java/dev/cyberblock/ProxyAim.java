package dev.cyberblock;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The scan pick (plan retest2 R2, M3): which proxy the crosshair means, from the proxies' authoritative server positions. The exact
 * camera ray used to have to cross a 0.6 m client box that trails the real NPC (in-game 2026-10-03: "nothing under the crosshair"
 * with 40 people around), so each box counts with a margin around it, and of the boxes the ray crosses the one nearest the line of
 * sight wins. Pure: no world in here.
 */
public final class ProxyAim {
	/** Metres around a pedestrian's box that still count as aiming at it. */
	public static final double HUMAN_MARGIN = 0.3;
	/** Metres around a vehicle's box. */
	public static final double VEHICLE_MARGIN = 0.5;

	private ProxyAim() {
	}

	/**
	 * Degrees between the ray and the direction to the box's centre, when the segment eye to eye + dir * reach crosses the box grown
	 * by margin; 0 when the eye is inside it; NaN when it misses (or the ray is not a ray).
	 */
	public static double score(final Vec3 eye, final Vec3 dir, final double reach, final AABB box, final double margin) {
		double length = dir.length();
		if (!(length > 0.0) || !Double.isFinite(length) || !(reach > 0.0) || !Double.isFinite(reach)) {
			return Double.NaN;
		}

		AABB grown = box.inflate(margin);
		if (grown.contains(eye)) {
			return 0.0;
		}

		Vec3 forward = dir.scale(1.0 / length);
		if (grown.clip(eye, eye.add(forward.scale(reach))).isEmpty()) {
			return Double.NaN;
		}

		Vec3 to = box.getCenter().subtract(eye);
		double distance = to.length();
		return distance > 0.0 ? Math.toDegrees(Math.acos(Math.clamp(forward.dot(to) / distance, -1.0, 1.0))) : 0.0;
	}

	/** Of the candidates, the one with the lowest finite score; an equal score goes to the one whose centre is nearer. Null for none. */
	public static <T> @Nullable T best(final Vec3 eye, final Vec3 dir, final double reach, final Iterable<T> candidates,
		final Function<T, AABB> box, final ToDoubleFunction<T> margin) {
		T best = null;
		double bestScore = Double.NaN, bestDistanceSq = 0.0;
		for (T candidate : candidates) {
			AABB b = box.apply(candidate);
			double score = score(eye, dir, reach, b, margin.applyAsDouble(candidate));
			if (Double.isNaN(score)) {
				continue;
			}

			double distanceSq = b.getCenter().distanceToSqr(eye);
			if (best == null || score < bestScore || score == bestScore && distanceSq < bestDistanceSq) {
				best = candidate;
				bestScore = score;
				bestDistanceSq = distanceSq;
			}
		}

		return best;
	}
}
