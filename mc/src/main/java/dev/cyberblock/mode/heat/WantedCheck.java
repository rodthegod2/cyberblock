package dev.cyberblock.mode.heat;

/**
 * The runtime feature check of Phase 0 (e) for one host session (its CET check was not run): Server Heat sends {@code heat {tier}}
 * DOWN and the scripts set the real wanted level; the host's {@code stats.wanted} (UP, 1 Hz) shows whether it took. The first raise
 * starts the check; the wanted level reaching what was sent within {@link #WINDOW_TICKS} confirms the real path for the session,
 * otherwise the fallback (Minecraft squads hunt this host's Steve too) applies until the session ends. Pure; server ticks.
 */
public final class WantedCheck {
	/** How long the host's game gets to show the wanted level (15 s; stats arrive once a second). */
	public static final long WINDOW_TICKS = 300;

	public enum State {
		/** nothing sent yet: Night City is assumed to answer (no squads) */
		UNTESTED,
		/** a raise was sent; waiting for stats.wanted */
		PENDING,
		/** the real wanted level follows Server Heat: Night City's NCPD and Max-Tac answer for this host */
		CONFIRMED,
		/** it did not: Minecraft squads hunt this host as they hunt guests */
		FALLBACK
	}

	private State state = State.UNTESTED;
	private int expect;
	private long deadline;

	public State state() {
		return this.state;
	}

	/** A {@code heat} DOWN with {@code wanted} went to the host at {@code tick}. Only a raise above 0 starts (or extends) the check. */
	public void sent(final int wanted, final long tick) {
		if (wanted <= 0 || this.state == State.CONFIRMED || this.state == State.FALLBACK) {
			return;
		}

		if (this.state == State.UNTESTED || wanted > this.expect) {
			if (this.state == State.UNTESTED) {
				this.deadline = tick + WINDOW_TICKS;
			}

			this.state = State.PENDING;
			this.expect = Math.max(this.expect, wanted);
		}
	}

	/**
	 * The host's latest {@code stats.wanted} at {@code tick}; returns true when the state changed (the caller logs the one line saying
	 * which path is active). A wanted level of at least 1 confirms: the write took, even if Night City capped it.
	 */
	public boolean update(final int wanted, final long tick) {
		if (this.state != State.PENDING) {
			return false;
		}

		if (wanted > 0) {
			this.state = State.CONFIRMED;
			return true;
		}

		if (tick >= this.deadline) {
			this.state = State.FALLBACK;
			return true;
		}

		return false;
	}

	/** May Minecraft squads hunt this host? Only once the real wanted level is known not to follow. */
	public boolean squadsHunt() {
		return this.state == State.FALLBACK;
	}

	public int expected() {
		return this.expect;
	}
}
