package dev.cyberblock.client.figure;

import dev.cyberblock.figure.FigureTags;

/**
 * How a Night City person moves on a guest's screen (megaplan task 5-F1): idle, walk or run from the host's anim bits 1 and 2, or
 * (Phase 0 f fallback, the DLL derives them; this covers a host that sends none) from the speed the figure is drawn at; the limb swing
 * that goes with it; a ragdoll's limp tumble; the recoil of a shot. Pure: no game in here.
 */
public final class FigureAnim {
	public enum Gait {
		IDLE, WALK, RUN
	}

	/** m/s: slower is standing (the smoothing never quite settles at zero). */
	static final double WALK_SPEED = 0.3;
	/** m/s: a person this fast is running (a brisk walk is about 1.8). */
	static final double RUN_SPEED = 3.0;
	/** Seconds a ragdoll takes to fall flat. */
	static final double RAGDOLL_FALL = 0.45;

	private FigureAnim() {
	}

	/** Whether the host told walk or run itself (bits 1 or 2), rather than this client guessing from motion. */
	public static boolean fromBits(final int anim) {
		return (anim & (FigureTags.WALK | FigureTags.RUN)) != 0;
	}

	public static Gait gait(final int anim, final double speed) {
		if ((anim & FigureTags.RUN) != 0) {
			return Gait.RUN;
		}

		if ((anim & FigureTags.WALK) != 0) {
			return Gait.WALK;
		}

		return speed >= RUN_SPEED ? Gait.RUN : speed >= WALK_SPEED ? Gait.WALK : Gait.IDLE;
	}

	/** How far the limbs swing (vanilla's walkAnimationSpeed, 0..1). */
	public static float swing(final Gait gait, final double speed) {
		return switch (gait) {
			case IDLE -> 0.0F;
			case WALK -> (float) Math.clamp(speed / 4.0, 0.45, 0.75);
			case RUN -> 1.0F;
		};
	}

	/** How fast the stride phase runs (vanilla walkAnimationPos units per second): about one stride a second at a walk. */
	public static float strideRate(final Gait gait, final double speed) {
		return switch (gait) {
			case IDLE -> 0.0F;
			case WALK -> (float) Math.clamp(6.7 * speed, 8.0, 14.0);
			case RUN -> (float) Math.clamp(5.0 * speed, 16.0, 22.0);
		};
	}

	/**
	 * Degrees a ragdoll has tipped over `seconds` after it went limp: it falls flat within RAGDOLL_FALL, overshoots a little and
	 * settles at 90 (flat), never beyond 100.
	 */
	public static float ragdollTilt(final double seconds) {
		if (!(seconds > 0.0)) {
			return 0.0F;
		}

		if (seconds < RAGDOLL_FALL) {
			double k = seconds / RAGDOLL_FALL;
			return (float) (90.0 * k * k);
		}

		double t = seconds - RAGDOLL_FALL;
		return (float) Math.min(100.0, 90.0 + 10.0 * Math.exp(-t / 0.15) * Math.sin(t * 25.0));
	}

	/** Radians a firing arm kicks up: a short jolt every FIRE_PERIOD ticks (age in ticks). */
	public static float recoil(final float ageInTicks) {
		float phase = (ageInTicks % FIRE_PERIOD) / FIRE_PERIOD;
		return phase < 0.3F ? 0.35F * (1.0F - phase / 0.3F) : 0.0F;
	}

	/** Ticks between two shots of a firing figure (the muzzle flash comes with each). */
	public static final int FIRE_PERIOD = 4;
}
