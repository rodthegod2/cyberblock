package dev.cyberblock;

/**
 * Layer 4 elytra flight (plan 2026-10-03 M3), the rules as plain numbers so they can be tested: when a glide starts from the
 * host's falling V, when it ends, and how long the host's own fall damage is ignored around it. The client's Flight applies
 * them.
 */
public final class FlightRules {
	/** With a jump press, V's feet must have fallen faster than this (m/s) over the last 3 ticks. */
	static final double JUMP_FALL_SPEED = 1.0;
	/** Without one, a glide starts by itself after falling faster than this (m/s) for AUTO_NANOS. */
	static final double AUTO_FALL_SPEED = 15.0;
	static final long AUTO_NANOS = 500_000_000L;
	/** After touchdown the host's camera positions are ignored this long, until the host has placed V at the landing spot. */
	public static final long SETTLE_NANOS = 500_000_000L;
	/** The host's "fall" damage is ignored while gliding and this long after touchdown. */
	public static final long FALL_GRACE_NANOS = 2_000_000_000L;
	/** The protocol's limit for flight.v and proj.v (m/s). */
	public static final double MAX_SPEED = 200.0;

	private FlightRules() {
	}

	/**
	 * A glide starts with a usable elytra when the host's jump key is pressed while V falls faster than 1 m/s (over the last 3
	 * ticks), or by itself after V fell faster than 15 m/s for half a second.
	 */
	public static boolean start(final boolean jumpPressed, final boolean elytra, final double fallSpeed3, final long fastNanos) {
		return elytra && (jumpPressed && fallSpeed3 > JUMP_FALL_SPEED || fastNanos >= AUTO_NANOS);
	}

	/** The glide is over once Steve stands on something, or vanilla stopped it (elytra broke, water, the server said no). */
	public static boolean touchdown(final boolean onGround, final boolean fallFlying) {
		return onGround || !fallFlying;
	}

	/** Scales v (m/s) down to MAX_SPEED when it is faster; a non-finite v is no motion. */
	public static double[] capSpeed(final double vx, final double vy, final double vz) {
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (!Double.isFinite(speed)) {
			return new double[] {0.0, 0.0, 0.0};
		}

		double k = speed > MAX_SPEED ? MAX_SPEED / speed : 1.0;
		return new double[] {vx * k, vy * k, vz * k};
	}

	/**
	 * The host's feet, one sample per client tick that brought a new pose: how fast they fell over the last 3 ticks, how long
	 * they have been falling fast, and their velocity (m/s).
	 */
	public static final class Fall {
		private static final int SAMPLES = 4;
		private final long[] t = new long[SAMPLES];
		private final double[] x = new double[SAMPLES], y = new double[SAMPLES], z = new double[SAMPLES];
		private int count;
		private long fastSince;
		private boolean fast;

		public void reset() {
			this.count = 0;
			this.fast = false;
		}

		/** A new sample (time in nanoseconds, feet in metres); older than the last one is ignored. */
		public void sample(final long nanos, final double px, final double py, final double pz) {
			if (this.count > 0) {
				int last = (this.count - 1) % SAMPLES;
				long dt = nanos - this.t[last];
				if (dt <= 0L) {
					return;
				}

				boolean fastNow = (this.y[last] - py) / (dt / 1.0E9) > AUTO_FALL_SPEED;
				if (fastNow && !this.fast) {
					this.fastSince = this.t[last];
				}

				this.fast = fastNow;
			}

			int i = this.count % SAMPLES;
			this.t[i] = nanos;
			this.x[i] = px;
			this.y[i] = py;
			this.z[i] = pz;
			this.count++;
		}

		private int newest() {
			return (this.count - 1) % SAMPLES;
		}

		private int oldest() {
			return this.count < SAMPLES ? 0 : this.count % SAMPLES;
		}

		/** Downward speed (m/s) over the last 3 ticks (4 samples), 0 until there are 4. */
		public double speed3() {
			if (this.count < SAMPLES) {
				return 0.0;
			}

			return (this.y[this.oldest()] - this.y[this.newest()]) / ((this.t[this.newest()] - this.t[this.oldest()]) / 1.0E9);
		}

		/** How long (ns) the feet have been falling faster than AUTO_FALL_SPEED, 0 when they are not. */
		public long fastNanos() {
			return this.fast ? this.t[this.newest()] - this.fastSince : 0L;
		}

		/** Velocity (m/s) over the samples kept, capped to MAX_SPEED; zero with fewer than 2. */
		public double[] velocity() {
			if (this.count < 2) {
				return new double[] {0.0, 0.0, 0.0};
			}

			int a = this.oldest(), b = this.newest();
			double dt = (this.t[b] - this.t[a]) / 1.0E9;
			return capSpeed((this.x[b] - this.x[a]) / dt, (this.y[b] - this.y[a]) / dt, (this.z[b] - this.z[a]) / dt);
		}
	}
}
