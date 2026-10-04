package dev.cyberblock.client.holo;

/**
 * The Holo-Table's view of the miniature (pure, tested): an orthographic camera around a centre point, turned by {@code yaw}
 * (0 = north up, like the Atlas), tilted from straight down ({@code tilt} = 90 degrees, the Atlas view) to an oblique look
 * ({@link #MIN_TILT}), showing {@code radius} blocks from the centre to the edge of the view.
 *
 * <p>A world point (x, y, z) becomes, around the screen point (sx0, sy0) at {@code scale} GUI px per block:
 * {@code rx = dx cos(yaw) - dz sin(yaw)} (right), {@code rz = dx sin(yaw) + dz cos(yaw)} (toward the viewer on the ground),
 * {@code sx = sx0 + rx scale}, {@code sy = sy0 + (rz sin(tilt) - dy cos(tilt)) scale}; the larger {@code rz}, the nearer.
 */
public final class HoloCamera {
	public static final double MIN_TILT = Math.toRadians(25.0);
	public static final double MAX_TILT = Math.toRadians(90.0);
	public static final double MIN_RADIUS = 48.0;
	public static final double MAX_RADIUS = 2048.0;
	/** Radians per GUI pixel of drag. */
	static final double TURN = 0.01;

	private double cx, cy, cz;
	private double yaw;
	private double tilt;
	private double radius;

	public HoloCamera(final double cx, final double cz, final double radius) {
		this.cx = cx;
		this.cz = cz;
		this.yaw = 0.0;
		this.tilt = Math.toRadians(55.0);
		this.radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
	}

	public double cx() {
		return this.cx;
	}

	public double cy() {
		return this.cy;
	}

	public double cz() {
		return this.cz;
	}

	public double yaw() {
		return this.yaw;
	}

	public double tilt() {
		return this.tilt;
	}

	public double radius() {
		return this.radius;
	}

	/** The miniature's base height (its lowest ground), the height that stays at the screen centre. */
	public void setBase(final double y) {
		this.cy = y;
	}

	public void centreOn(final double x, final double z) {
		this.cx = x;
		this.cz = z;
	}

	/** A horizontal drag of {@code dx} GUI px turns the table. */
	public void turn(final double dx) {
		this.yaw = wrap(this.yaw + dx * TURN);
	}

	public void setYaw(final double radians) {
		this.yaw = wrap(radians);
	}

	/** A vertical drag of {@code dy} GUI px tilts it (down = toward the top-down view). */
	public void tiltBy(final double dy) {
		this.tilt = Math.clamp(this.tilt + dy * TURN, MIN_TILT, MAX_TILT);
	}

	public void setTilt(final double radians) {
		this.tilt = Math.clamp(radians, MIN_TILT, MAX_TILT);
	}

	/** Zoom by {@code factor} (above 1 = closer). */
	public void zoom(final double factor) {
		this.radius = Math.clamp(this.radius / factor, MIN_RADIUS, MAX_RADIUS);
	}

	/** A drag by (dx, dy) GUI px at {@code scale} px per block moves the ground under the cursor with it. */
	public void pan(final double dx, final double dy, final double scale) {
		double rx = -dx / scale, rz = -dy / (scale * Math.sin(this.tilt));
		double c = Math.cos(this.yaw), s = Math.sin(this.yaw);
		this.cx += c * rx + s * rz;
		this.cz += -s * rx + c * rz;
	}

	/** Screen x of a world point. */
	public double sx(final double x, final double z, final double sx0, final double scale) {
		return sx0 + ((x - this.cx) * Math.cos(this.yaw) - (z - this.cz) * Math.sin(this.yaw)) * scale;
	}

	/** Screen y of a world point. */
	public double sy(final double x, final double y, final double z, final double sy0, final double scale) {
		double rz = (x - this.cx) * Math.sin(this.yaw) + (z - this.cz) * Math.cos(this.yaw);
		return sy0 + (rz * Math.sin(this.tilt) - (y - this.cy) * Math.cos(this.tilt)) * scale;
	}

	/** How near a ground point is to the viewer (larger = nearer): the painter's order. */
	public double depth(final double x, final double z) {
		return (x - this.cx) * Math.sin(this.yaw) + (z - this.cz) * Math.cos(this.yaw);
	}

	/** Does a vertical face whose outward normal is (nx, nz) face the viewer? */
	public boolean faces(final double nx, final double nz) {
		return nx * Math.sin(this.yaw) + nz * Math.cos(this.yaw) > 1e-9;
	}

	private static double wrap(final double a) {
		double t = a % (2 * Math.PI);
		return t < 0 ? t + 2 * Math.PI : t;
	}
}
