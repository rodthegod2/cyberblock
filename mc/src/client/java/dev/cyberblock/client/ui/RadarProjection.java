package dev.cyberblock.client.ui;

/**
 * The radar's maths (pure, tested): world offsets around Steve to radar pixels, for a radar that turns with the host camera so
 * that straight ahead is up. Minecraft yaw: 0 faces +Z (south), 90 faces -X (west); forward = (-sin yaw, cos yaw), right =
 * (-cos yaw, -sin yaw). Screen y grows downward.
 */
public final class RadarProjection {
	/** A radar point in pixels from the centre; {@code inside} when it lies within the radar square. */
	public record Point(float x, float y, boolean inside) {
	}

	private RadarProjection() {
	}

	/** Where the world offset (dx, dz) lands, {@code scale} pixels per block, in a square of half-size {@code half} pixels. */
	public static Point project(final double dx, final double dz, final float yawDegrees, final double scale, final double half) {
		double yaw = Math.toRadians(yawDegrees);
		double sin = Math.sin(yaw), cos = Math.cos(yaw);
		double right = -dx * cos - dz * sin;
		double forward = -dx * sin + dz * cos;
		float x = (float) (right * scale), y = (float) (-forward * scale);
		return new Point(x, y, Math.abs(x) <= half && Math.abs(y) <= half);
	}

	/** The point pulled in along its ray to the square's edge (the objective edge arrow); a point inside is returned as is. */
	public static Point clampToEdge(final Point p, final double half) {
		float m = Math.max(Math.abs(p.x()), Math.abs(p.y()));
		if (m <= half || m == 0.0F) {
			return p;
		}

		float k = (float) (half / m);
		return new Point(p.x() * k, p.y() * k, false);
	}

	/** The bearing of (dx, dz) from the facing yaw, degrees in (-180, 180]: 0 straight ahead, positive to the right. */
	public static double bearing(final double dx, final double dz, final float yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);
		double forward = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
		double deg = Math.toDegrees(Math.atan2(right, forward));
		return deg <= -180.0 ? deg + 360.0 : deg;
	}

	/**
	 * The pose rotation (radians, for Matrix3x2f.rotate around the centre) that turns a north-up world grid drawn at local
	 * (-dx·scale, -dz·scale) into {@link #project}: the terrain cells are drawn once in world axes and rotated as a whole.
	 */
	public static float gridRotation(final float yawDegrees) {
		return (float) -Math.toRadians(yawDegrees);
	}
}
