package dev.cyberblock.mode.heat;

import java.util.List;

/**
 * The server-wide meter 0..100 (megaplan 8-T1): fed by chaos through a gain budget ({@link HeatRules#GAIN_PER_SECOND}, burst
 * {@link HeatRules#GAIN_BURST}), cooled by {@link HeatRules#decay}. Ticks are server ticks (20 per second). Not thread-safe: the
 * server thread owns it.
 */
public final class HeatMeter {
	private double value;
	private double budget = HeatRules.GAIN_BURST;
	private long lastChaos = Long.MIN_VALUE / 2;
	private long lastTick;

	public double value() {
		return this.value;
	}

	/** Adds chaos at {@code tick}; returns what the meter really gained (the budget and the 100 cap may cut it). */
	public double feed(final double amount, final long tick) {
		this.refill(tick);
		if (!(amount > 0.0)) {
			return 0.0;
		}

		double gain = Math.min(Math.min(amount, this.budget), HeatRules.MAX - this.value);
		this.budget -= Math.max(0.0, gain);
		this.value = HeatRules.feed(this.value, gain);
		this.lastChaos = tick;
		return Math.max(0.0, gain);
	}

	/** Cools the meter up to {@code tick} (call about once a second). */
	public void tick(final long tick, final List<HeatTier> sorted) {
		long dt = tick - this.lastTick;
		this.refill(tick);
		if (dt > 0) {
			this.value = HeatRules.decay(this.value, dt / 20.0, tick - this.lastChaos, sorted);
		}
	}

	private void refill(final long tick) {
		long dt = tick - this.lastTick;
		if (dt > 0) {
			this.budget = Math.min(HeatRules.GAIN_BURST, this.budget + HeatRules.GAIN_PER_SECOND * dt / 20.0);
			this.lastTick = tick;
		}
	}

	/** An operator's /cyberblock heat set (no budget, and it counts as fresh chaos). */
	public void set(final double v, final long tick) {
		this.refill(tick);
		this.value = Math.clamp(v, 0.0, HeatRules.MAX);
		this.lastChaos = tick;
	}

	public long lastChaos() {
		return this.lastChaos;
	}
}
