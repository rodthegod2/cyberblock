package dev.cyberblock.client.figure;

/**
 * Where one figure is drawn (plan 2026-10-04 tasks 3-F1 and 5-F1: real speed, smoothed client-side over two of the host's 10 Hz
 * samples, no rubber-banding). The server runs each proxy on at its last
 * speed between the host's lists (ProxyDiff.Motion, at most 0.5 s) and corrects it when the next list comes; the part of a move that
 * the figure's recent speed did not predict is such a correction, and the figure eases into it over about TAU seconds instead of
 * jumping. A move longer than SNAP is a teleport: the figure is just there. Pure: no game in here.
 */
public final class FigureSmoothing {
	/** Metres: a farther move between two frames is a teleport. */
	static final double SNAP = 6.0;
	/** Seconds a correction takes to fade (to 1/e). */
	static final double TAU = 0.15;
	/**
	 * Seconds over which the figure's speed follows the target's: two of the host's samples at 10 Hz (megaplan task 5-F1). Over one
	 * sample a list that arrives late or early swings the speed enough to pull the figure back a few centimetres (rubber-banding).
	 */
	static final double SPEED_TAU = 0.2;
	/** Metres: the drawn figure never trails its hitbox by more than this. */
	static final double MAX_OFFSET = 1.5;
	/** Seconds: a longer gap between frames (a pause, a lag spike) counts as this long. */
	private static final double MAX_DT = 0.25;

	private boolean started;
	private long nanos;
	private double tx, ty, tz, ox, oy, oz, vx, vy, vz;

	/** m/s over the ground the figure is drawn moving at (its smoothed speed): what the walk and run fallback goes by. */
	public double speed() {
		return Math.sqrt(this.vx * this.vx + this.vz * this.vz);
	}

	/** Where to draw it this frame (nanos: System.nanoTime) for the game's position of it: {x, y, z}. */
	public double[] at(final double x, final double y, final double z, final long now) {
		double dt = (now - this.nanos) / 1.0E9;
		if (!this.started || !(dt > 0.0)) {
			if (!this.started) {
				this.started = true;
				this.nanos = now;
				this.tx = x;
				this.ty = y;
				this.tz = z;
			}

			return new double[] {x + this.ox, y + this.oy, z + this.oz};
		}

		dt = Math.min(dt, MAX_DT);
		double dx = x - this.tx, dy = y - this.ty, dz = z - this.tz;
		if (dx * dx + dy * dy + dz * dz > SNAP * SNAP) {
			this.ox = this.oy = this.oz = this.vx = this.vy = this.vz = 0.0;
		} else {
			double fade = Math.exp(-dt / TAU), follow = 1.0 - Math.exp(-dt / SPEED_TAU);
			this.ox = (this.ox - (dx - this.vx * dt)) * fade;
			this.oy = (this.oy - (dy - this.vy * dt)) * fade;
			this.oz = (this.oz - (dz - this.vz * dt)) * fade;
			double length = Math.sqrt(this.ox * this.ox + this.oy * this.oy + this.oz * this.oz);
			if (length > MAX_OFFSET) {
				double k = MAX_OFFSET / length;
				this.ox *= k;
				this.oy *= k;
				this.oz *= k;
			}

			this.vx += (dx / dt - this.vx) * follow;
			this.vy += (dy / dt - this.vy) * follow;
			this.vz += (dz / dt - this.vz) * follow;
		}

		this.nanos = now;
		this.tx = x;
		this.ty = y;
		this.tz = z;
		return new double[] {x + this.ox, y + this.oy, z + this.oz};
	}
}
