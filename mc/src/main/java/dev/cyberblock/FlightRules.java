package dev.cyberblock;

/**
 * Layer 4 elytra flight (plan 2026-10-03 M3), the rules as plain numbers so they can be tested: when a glide starts from the
 * host's falling V, when the host is following it, when it ends, and how long the host's own fall damage is ignored around
 * it. The client's Flight applies them.
 */
public final class FlightRules {
	/** With a jump press, V's feet must fall faster than this (m/s) between the newest two samples. */
	static final double JUMP_FALL_SPEED = 1.0;
	/** Without one, a glide starts by itself after falling faster than this (m/s) for AUTO_NANOS. */
	static final double AUTO_FALL_SPEED = 12.0;
	static final long AUTO_NANOS = 300_000_000L;
	/**
	 * Free air (blocks) needed below V's feet: a glide opened lower touches down at once (in-game 2026-10-03: glides opened
	 * 0.4 m above the ground and ended within the second).
	 */
	public static final double MIN_CLEARANCE = 3.0;
	/** The host must say it follows a glide (cam.fly) this soon after the start, or the glide is called off. */
	static final long FOLLOW_NANOS = 500_000_000L;
	/** After a glide was called off, no new one starts for this long. */
	public static final long RETRY_NANOS = 1_000_000_000L;
	/** After touchdown the host's camera positions are ignored this long, until the host has placed V at the landing spot. */
	public static final long SETTLE_NANOS = 500_000_000L;
	/** The host's "fall" damage is ignored while gliding and this long after touchdown. */
	public static final long FALL_GRACE_NANOS = 2_000_000_000L;
	/** The protocol's limit for flight.v and proj.v (m/s). */
	public static final double MAX_SPEED = 200.0;
	/**
	 * Plan retest2 R5: an explosion's knockback (blocks per tick; his own wind charge at his feet gives 1.22 up) launches Steve when
	 * it lifts him at least this much...
	 */
	public static final double LAUNCH_MIN_UP = 0.25;
	/** ...and is at least this fast. */
	public static final double LAUNCH_MIN_SPEED = 0.4;
	/** Any other knockback the server sends (a motion packet) must lift more: a hit's knockback is 0.4 up. */
	public static final double MOTION_LAUNCH_MIN_UP = 0.6;
	/** A launch cannot land before this: the tick of the launch may still say on the ground. */
	public static final long LAUNCH_SETTLE_NANOS = 100_000_000L;
	/** After touchdown, V standing this close to the landing spot (no longer following) ends the settle early (the DLL's kNearLand). */
	public static final double NEAR_LAND = 1.5;
	/**
	 * While the host follows, its V more than this many blocks from Steve for APART_NANOS in a row means V is not with him (V stopped
	 * at Night City geometry Minecraft does not have, or a host that says it follows but cannot): the glide is called off and Steve
	 * goes back to V. In-game 2026-10-03 V was held at a wall while Steve glided on alone for 11 s. A dive at 70 m/s trails V by
	 * about 10 blocks (the host plays the samples out up to 60 ms late, plus the link), so the bound sits above that.
	 */
	public static final double MAX_APART = 16.0;
	public static final long APART_NANOS = 1_000_000_000L;

	private FlightRules() {
	}

	/**
	 * Whether a knockback (blocks per tick) launches Steve off V's ground: upward by LAUNCH_MIN_UP (MOTION_LAUNCH_MIN_UP for a
	 * motion packet) and at least LAUNCH_MIN_SPEED fast. Not a finite vector: no.
	 *
	 * @param explosion the kick came with an explosion (its playerKnockback), not a motion packet
	 */
	public static boolean launch(final double vx, final double vy, final double vz, final boolean explosion) {
		if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
			return false;
		}

		return vy >= (explosion ? LAUNCH_MIN_UP : MOTION_LAUNCH_MIN_UP) && Math.sqrt(vx * vx + vy * vy + vz * vz) >= LAUNCH_MIN_SPEED;
	}

	/** A launch is over once Steve is on the ground or in water, and LAUNCH_SETTLE_NANOS have passed since it started. */
	public static boolean launchLanded(final boolean onGround, final boolean inWater, final long sinceStartNanos) {
		return sinceStartNanos >= LAUNCH_SETTLE_NANOS && (onGround || inWater);
	}

	/**
	 * In a launch, a jump press opens the elytra as a glide start would: Steve falls faster than JUMP_FALL_SPEED (m/s) with at least
	 * MIN_CLEARANCE of air below.
	 */
	public static boolean launchToGlide(final boolean jumpPressed, final boolean elytra, final double fallSpeed, final double clearance) {
		return jumpPressed && elytra && fallSpeed > JUMP_FALL_SPEED && clearance >= MIN_CLEARANCE;
	}

	/**
	 * After touchdown, whether Steve may follow V again: SETTLE_NANOS after it at the latest, at once when a pose that arrived after
	 * touchdown says the host no longer follows (cam.fly false) and V's feet are within NEAR_LAND of the landing spot.
	 *
	 * @param distance metres from that pose's feet to the landing spot
	 */
	public static boolean settled(final long now, final long touchdownNanos, final long poseNanos, final boolean fly, final double distance) {
		return now - touchdownNanos >= SETTLE_NANOS || poseNanos - touchdownNanos > 0L && !fly && distance < NEAR_LAND;
	}

	/**
	 * A glide starts with a usable elytra, at least MIN_CLEARANCE of free air below V's feet and room for Steve's gliding box
	 * there: when a glide press of the host's jump key comes while V falls faster than 1 m/s, or by itself after V fell faster
	 * than 12 m/s for 0.3 s.
	 *
	 * @param fallSpeed V's downward speed between the newest two samples (Fall.speed)
	 * @param clearance free air below V's feet, in blocks (barriers count)
	 * @param overlapping Steve's gliding box at V's feet already intersects a block or barrier (V stands inside the rounded-up
	 *     barrier of Night City's ground)
	 */
	public static boolean start(final boolean jumpPressed, final boolean elytra, final double fallSpeed, final long fastNanos,
		final double clearance, final boolean overlapping) {
		if (!elytra || overlapping || !(clearance >= MIN_CLEARANCE)) {
			return false;
		}

		return jumpPressed && fallSpeed > JUMP_FALL_SPEED || fastNanos >= AUTO_NANOS;
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
	 * Presses of the host's jump key while V is in the air (cam.air). With the Double Jump perk the first one is V's own double
	 * jump, so only the ones after it may open the elytra. A press on the ground is passed on (it cannot open the elytra: V is not
	 * falling with air below). The count starts again whenever V is on the ground.
	 */
	public static final class AirJumps {
		private int presses;

		public void reset() {
			this.presses = 0;
		}

		/** One client tick: true when this tick has a jump press that may open the elytra. */
		public boolean press(final boolean air, final boolean pressed, final boolean doubleJump) {
			if (!air) {
				this.presses = 0;
				return pressed;
			}

			if (!pressed) {
				return false;
			}

			this.presses++;
			return !doubleJump || this.presses >= 2;
		}
	}

	/**
	 * Whether the host follows the glide: its cam.fly must turn true within FOLLOW_NANOS of the start, and stay true. Only poses
	 * that arrived after the start count (an older one says nothing about this glide).
	 */
	public static final class Follow {
		private long start;
		private boolean seen;
		private boolean apart;
		private long apartSince;

		public void start(final long nanos) {
			this.start = nanos;
			this.seen = false;
			this.apart = false;
		}

		/** The host has said it follows this glide. */
		public boolean seen() {
			return this.seen;
		}

		/** A pause (a Cyberpunk menu froze the glide): a host that has not followed yet gets the full time again from now. */
		public void restart(final long nanos) {
			if (!this.seen) {
				this.start = nanos;
			}

			this.apart = false;
		}

		/**
		 * True when the glide must be called off because the host's V, though it follows, has been more than MAX_APART blocks from
		 * Steve for APART_NANOS: one call per client tick with the distance between Steve and the latest pose's feet. Before the host
		 * follows, or with a distance that is not a number, nothing is apart.
		 */
		public boolean tooFarApart(final long now, final double distance) {
			if (!this.seen || !(distance > MAX_APART)) {
				this.apart = false;
				return false;
			}

			if (!this.apart) {
				this.apart = true;
				this.apartSince = now;
			}

			return now - this.apartSince >= APART_NANOS;
		}

		/**
		 * False when the glide must be called off: the host stopped following (fly false after it was true), or it never followed
		 * within FOLLOW_NANOS.
		 *
		 * @param poseNanos when the latest pose arrived
		 * @param fly that pose's cam.fly
		 */
		public boolean ok(final long now, final long poseNanos, final boolean fly) {
			if (poseNanos - this.start > 0L) {
				if (fly) {
					this.seen = true;
				} else if (this.seen) {
					return false;
				}
			}

			return this.seen || now - this.start < FOLLOW_NANOS;
		}
	}

	/**
	 * The host's feet, one sample per client tick that brought a new pose: how fast they fall now, how long they have been falling
	 * fast, and their velocity (m/s).
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

		/**
		 * Downward speed (m/s) between the newest two samples, 0 until there are 2. Not an average over 3 ticks: that still held
		 * the rise of a jump, or missed the start of the fall, when the key was pressed again near its top.
		 */
		public double speed() {
			if (this.count < 2) {
				return 0.0;
			}

			int b = this.newest(), a = (this.count - 2) % SAMPLES;
			return (this.y[a] - this.y[b]) / ((this.t[b] - this.t[a]) / 1.0E9);
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
