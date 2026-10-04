package dev.cyberblock.client.view;

/**
 * Q-V1: where Steve is drawn between the host's poses. Keeps the last two cam samples (V's feet and the time each arrived) and
 * gives the position for any time: interpolated between the two by receive time, extrapolated past the newest by at most
 * {@link #MAX_EXTRAPOLATION_NANOS}.
 *
 * <p>Steve is drawn for the arrival of the pose the frame is rendered and exported with (PlayerSync.renderFeet). The
 * compositor re-projects that frame to Cyberpunk's newer camera and moves Steve's capsule by V's motion since that cam
 * (Cyberblock.fx cb_body_move, commit 75e6778), so he must be drawn exactly at that cam's feet; drawing him further ahead here
 * as well would move him twice. Not thread-safe: render thread only.
 */
public final class ViewInterp {
	/** How far past the newest sample a position is extrapolated at most. */
	public static final long MAX_EXTRAPOLATION_NANOS = 50_000_000L;

	private long t0, t1;
	private int count;
	private double x0, y0, z0, x1, y1, z1;

	/** A sample: V's feet as the cam that arrived at {@code nanos} had them. The same arrival twice is one sample. */
	public void sample(final long nanos, final double x, final double y, final double z) {
		if (count > 0 && nanos == t1) {
			return;
		}

		t0 = t1;
		x0 = x1;
		y0 = y1;
		z0 = z1;
		t1 = nanos;
		x1 = x;
		y1 = y;
		z1 = z;
		count = Math.min(count + 1, 2);
	}

	/** Forget every sample (no host). */
	public void reset() {
		count = 0;
	}

	/**
	 * The interpolation factor for {@code nanos}: 0 at the older sample, 1 at the newest, above 1 when extrapolating (capped at
	 * MAX_EXTRAPOLATION_NANOS past the newest), never below 0. 1 with fewer than two samples or two at the same time.
	 */
	public double factor(final long nanos) {
		long dt = t1 - t0;
		if (count < 2 || dt <= 0L) {
			return 1.0;
		}

		double max = 1.0 + (double) MAX_EXTRAPOLATION_NANOS / dt;
		return Math.clamp((double) (nanos - t0) / dt, 0.0, max);
	}

	/** The position for {@code nanos} ({@link #factor}), as {x, y, z}; NaNs before the first sample. */
	public double[] at(final long nanos) {
		if (count == 0) {
			return new double[] {Double.NaN, Double.NaN, Double.NaN};
		}

		double f = factor(nanos);
		return count < 2 ? new double[] {x1, y1, z1} : new double[] {x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, z0 + (z1 - z0) * f};
	}

	/** Milliseconds between the last two samples (the cam interval), NaN with fewer than two. */
	public double intervalMillis() {
		return count < 2 ? Double.NaN : (t1 - t0) / 1.0E6;
	}
}
