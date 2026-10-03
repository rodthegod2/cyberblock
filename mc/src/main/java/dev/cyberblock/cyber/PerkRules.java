package dev.cyberblock.cyber;

/** The pure parts of the perks' Minecraft effects (task C7, plan section 1.5): timings, cones and multipliers. */
public final class PerkRules {
	/** Sandevistan: two sprint presses at most this far apart. */
	public static final long DOUBLE_TAP_MS = 300L;
	public static final long SANDEVISTAN_MS = 5000L;
	public static final long SANDEVISTAN_COOLDOWN_MS = 20000L;
	/** The server's tick rate while the Sandevistan runs, and the normal one. */
	public static final float SLOW_TICK_RATE = 10.0F;
	public static final float NORMAL_TICK_RATE = 20.0F;
	/** Optical camo: crouched and within this distance of where the stillness began, for CAMO_STILL_MS. */
	public static final double CAMO_STILL_METRES = 0.1;
	public static final long CAMO_STILL_MS = 2000L;
	/** Invisibility given, refreshed every CAMO_REFRESH_MS while still (each refresh is one `effect` for Cyberpunk). */
	public static final int CAMO_TICKS = 200;
	public static final long CAMO_REFRESH_MS = 1000L;
	/** Smart arrows: turn at most this much per tick toward a proxy within CONE_DEGREES of the flight direction and RANGE. */
	public static final double HOMING_DEGREES_PER_TICK = 6.0;
	public static final double CONE_DEGREES = 20.0;
	public static final double HOMING_RANGE = 32.0;
	public static final float QUICK_DRAW = 0.7F;
	public static final float FIELD_MEDIC = 1.5F;
	public static final double SILENT_STEPS = 0.5;

	private PerkRules() {
	}

	/** Sprint presses; fires on the second of two presses within DOUBLE_TAP_MS (a third press starts over). */
	public static final class DoubleTap {
		private long last = Long.MIN_VALUE;

		public boolean press(final long nowMs) {
			if (this.last != Long.MIN_VALUE && nowMs - this.last <= DOUBLE_TAP_MS) {
				this.last = Long.MIN_VALUE;
				return true;
			}

			this.last = nowMs;
			return false;
		}
	}

	/** May the Sandevistan fire again (lastFiredMs: Long.MIN_VALUE for never)? */
	public static boolean sandevistanReady(final long nowMs, final long lastFiredMs) {
		return lastFiredMs == Long.MIN_VALUE || nowMs - lastFiredMs >= SANDEVISTAN_COOLDOWN_MS;
	}

	/** Optical camo's stillness timer: true when the invisibility should be given (again) now. */
	public static final class Camo {
		private boolean still;
		private double x;
		private double y;
		private double z;
		private long since;
		private long lastGiven = Long.MIN_VALUE;

		public boolean update(final boolean crouched, final double px, final double py, final double pz, final long nowMs) {
			double dx = px - this.x, dy = py - this.y, dz = pz - this.z;
			if (!crouched || !this.still || dx * dx + dy * dy + dz * dz >= CAMO_STILL_METRES * CAMO_STILL_METRES) {
				this.still = crouched;
				this.x = px;
				this.y = py;
				this.z = pz;
				this.since = nowMs;
				this.lastGiven = Long.MIN_VALUE;
				return false;
			}

			if (nowMs - this.since < CAMO_STILL_MS || this.lastGiven != Long.MIN_VALUE && nowMs - this.lastGiven < CAMO_REFRESH_MS) {
				return false;
			}

			this.lastGiven = nowMs;
			return true;
		}
	}

	/** Is (dx, dy, dz) from the arrow within the cone around its velocity, and in range? */
	public static boolean inCone(final double vx, final double vy, final double vz, final double dx, final double dy, final double dz) {
		double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (v < 1.0E-6 || d < 1.0E-6 || d > HOMING_RANGE) {
			return false;
		}

		double cos = (vx * dx + vy * dy + vz * dz) / (v * d);
		return cos >= Math.cos(Math.toRadians(CONE_DEGREES));
	}

	/** The velocity turned toward (dx, dy, dz) by at most HOMING_DEGREES_PER_TICK, its speed kept. */
	public static double[] steer(final double vx, final double vy, final double vz, final double dx, final double dy, final double dz) {
		double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (v < 1.0E-6 || d < 1.0E-6) {
			return new double[]{vx, vy, vz};
		}

		double ux = vx / v, uy = vy / v, uz = vz / v;
		double tx = dx / d, ty = dy / d, tz = dz / d;
		double angle = Math.acos(Math.clamp(ux * tx + uy * ty + uz * tz, -1.0, 1.0));
		double max = Math.toRadians(HOMING_DEGREES_PER_TICK);
		if (angle <= max) {
			return new double[]{tx * v, ty * v, tz * v};
		}

		// rotate u toward t in their plane: the part of t perpendicular to u, normalised, gives the turning direction
		double px = tx - ux * Math.cos(angle), py = ty - uy * Math.cos(angle), pz = tz - uz * Math.cos(angle);
		double p = Math.sqrt(px * px + py * py + pz * pz);
		double c = Math.cos(max), s = Math.sin(max);
		return new double[]{(ux * c + px / p * s) * v, (uy * c + py / p * s) * v, (uz * c + pz / p * s) * v};
	}

	/** Quick draw, bow: the time held counts 1/0.7 as much (full power after 14 ticks instead of 20). */
	public static int bowTimeHeld(final int ticks) {
		return Math.round(ticks / QUICK_DRAW);
	}

	/** Quick draw, crossbow: the charge takes 0.7 as long, at least a tick. */
	public static int crossbowCharge(final int ticks) {
		return Math.max(1, Math.round(ticks * QUICK_DRAW));
	}

	/** Tinkerer: twice the crafted TNT or arrows, up to a full stack. */
	public static int craftedCount(final int count, final int maxStack) {
		return Math.min(maxStack, count * 2);
	}

	/** Field medic, instant health: half as much again. */
	public static float heal(final float amount) {
		return amount * FIELD_MEDIC;
	}

	/** Field medic, food: the extra nutrition eaten on top of the normal (half again, rounded). */
	public static int extraNutrition(final int nutrition) {
		return Math.round(nutrition * (FIELD_MEDIC - 1.0F));
	}

	public static float extraSaturation(final float saturation) {
		return saturation * (FIELD_MEDIC - 1.0F);
	}

	/** Silent steps: mobs see the player as if from twice as far. */
	public static double visibility(final double percent) {
		return percent * SILENT_STEPS;
	}
}
