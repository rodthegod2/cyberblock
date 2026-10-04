package dev.cyberblock.mode.overlord;

/**
 * One match's Threat Points (megaplan 8-O1): they regenerate at the intensity's rate up to its bank, and every spawn or event
 * spends its content entry's cost when it is queued (a palette entry is one spawn action, so a bigger spawn is a dearer entry).
 * Server thread.
 */
public final class ThreatPoints {
	private double points;

	public ThreatPoints(final double start) {
		this.points = Math.max(0.0, start);
	}

	public double points() {
		return this.points;
	}

	/** Whole points, as the console shows them. */
	public int whole() {
		return (int) Math.floor(this.points);
	}

	/** {@code seconds} of regeneration at the intensity's rate, never above its bank (a lowered intensity trims the bank). */
	public void regen(final double seconds, final Intensity intensity) {
		if (seconds > 0.0 && Double.isFinite(seconds)) {
			this.points += seconds * intensity.regen();
		}

		this.points = Math.min(this.points, intensity.maxPoints());
	}

	public boolean canAfford(final int cost) {
		return cost >= 0 && this.points >= cost;
	}

	/** Takes {@code cost} when there is enough; false (nothing taken) otherwise. */
	public boolean spend(final int cost) {
		if (!this.canAfford(cost)) {
			return false;
		}

		this.points -= cost;
		return true;
	}

	/** Gives points back (a spawn that could not be placed at all). */
	public void refund(final int cost, final Intensity intensity) {
		if (cost > 0) {
			this.points = Math.min(this.points + cost, intensity.maxPoints());
		}
	}
}
