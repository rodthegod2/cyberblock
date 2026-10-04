package dev.cyberblock.mode.downed;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One downed player (megaplan 8-T2), pure so the tests can drive it with server ticks: the bleed-out timer ({@link #BLEED_TICKS}),
 * a teammate's revive (right-click held for {@link #REVIVE_TICKS}; the client repeats the use packet every 4 ticks while the button
 * is held, so a gap longer than {@link #HOLD_GAP_TICKS} means it was let go), the Trauma Team flyer ({@link #TRAUMA_TICKS} after the
 * call; the bleed-out waits while it is on its way) and the hits a downed player takes ({@link #HIT_PENALTY_TICKS} off the timer, at
 * most once a second).
 */
public final class DownState {
	public static final long BLEED_TICKS = 1200;
	public static final long REVIVE_TICKS = 80;
	public static final long HOLD_GAP_TICKS = 10;
	public static final long TRAUMA_TICKS = 600;
	public static final long HIT_PENALTY_TICKS = 100;
	static final long HIT_COOLDOWN_TICKS = 20;

	/** What a tick ended in. */
	public enum Outcome {
		NONE, BLED_OUT, TRAUMA_ARRIVED
	}

	private long bleedLeft = BLEED_TICKS;
	private long traumaLeft = -1;
	private @Nullable UUID reviver;
	private long holdStart, holdLast = Long.MIN_VALUE / 2;
	private long lastHit = Long.MIN_VALUE / 2;

	/** One server tick. */
	public Outcome tick() {
		if (this.traumaLeft >= 0) {
			return --this.traumaLeft <= 0 ? Outcome.TRAUMA_ARRIVED : Outcome.NONE;
		}

		return --this.bleedLeft <= 0 ? Outcome.BLED_OUT : Outcome.NONE;
	}

	/**
	 * {@code who} holds right-click on this player at {@code tick}. True when the hold reached {@link #REVIVE_TICKS} (revived). Another
	 * reviver, or the same one after letting go, starts over.
	 */
	public boolean hold(final UUID who, final long tick) {
		if (!who.equals(this.reviver) || tick - this.holdLast > HOLD_GAP_TICKS || tick < this.holdLast) {
			this.reviver = who;
			this.holdStart = tick;
		}

		this.holdLast = tick;
		return tick - this.holdStart >= REVIVE_TICKS;
	}

	/** The revive in progress 0..1 at {@code tick} (0 when nobody holds). */
	public float revive(final long tick) {
		if (this.reviver == null || tick - this.holdLast > HOLD_GAP_TICKS) {
			return 0.0F;
		}

		return Math.clamp((float) (this.holdLast - this.holdStart) / REVIVE_TICKS, 0.0F, 1.0F);
	}

	/** Who is reviving at {@code tick}, or null. */
	public @Nullable UUID reviver(final long tick) {
		return this.reviver != null && tick - this.holdLast <= HOLD_GAP_TICKS ? this.reviver : null;
	}

	/** The flyer is called; false when it already is. */
	public boolean callTrauma() {
		if (this.traumaLeft >= 0) {
			return false;
		}

		this.traumaLeft = TRAUMA_TICKS;
		return true;
	}

	/** The flyer turned back (the player was revived first). */
	public void cancelTrauma() {
		this.traumaLeft = -1;
	}

	/** A hit on the downed player at {@code tick}: true when it cost bleed-out time (not while the flyer is on its way). */
	public boolean hit(final long tick) {
		if (this.traumaLeft >= 0 || tick - this.lastHit < HIT_COOLDOWN_TICKS) {
			return false;
		}

		this.lastHit = tick;
		this.bleedLeft = Math.max(1, this.bleedLeft - HIT_PENALTY_TICKS);
		return true;
	}

	/** Give up: the next tick bleeds out. */
	public void giveUp() {
		this.traumaLeft = -1;
		this.bleedLeft = 1;
	}

	public long bleedLeft() {
		return this.bleedLeft;
	}

	/** Ticks until the flyer lands, or -1 when none is on its way. */
	public long traumaLeft() {
		return this.traumaLeft;
	}

	public boolean traumaCalled() {
		return this.traumaLeft >= 0;
	}
}
