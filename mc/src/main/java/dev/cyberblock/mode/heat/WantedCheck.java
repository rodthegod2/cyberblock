package dev.cyberblock.mode.heat;

/**
 * The runtime feature check of Phase 0 (e) for one host session (its CET check was not run): Server Heat sends {@code heat {tier}}
 * DOWN and the scripts set the real wanted level; the host's {@code stats.wanted} (UP, 1 Hz) shows whether it took.
 *
 * <p>A raise above the host's wanted level at the time it is sent starts a test: the wanted level must rise to at least
 * {@code min(sent, before + 1)} within {@link #WINDOW_TICKS} (longer than 8S's 30 s retry of a raise it held back in a scene or a
 * police-free area). Stars V already had from Night City crimes therefore never confirm by themselves, and a raise sent while V is at
 * or above it tests nothing. A failed test is the fallback (Minecraft squads hunt this host's Steve too) until the next raise, which
 * tests again; a confirmed path stays for the session. Pure; server ticks.
 */
public final class WantedCheck {
	/** How long the host's game gets to show the wanted level (60 s; stats arrive once a second; 8S retries a held raise after 30 s). */
	public static final long WINDOW_TICKS = 1200;

	public enum State {
		/** nothing tested yet: Night City is assumed to answer (no squads) */
		UNTESTED,
		/** a raise was sent; waiting for stats.wanted (squads hunt meanwhile only when the last test failed) */
		PENDING,
		/** the real wanted level follows Server Heat: Night City's NCPD and Max-Tac answer for this host */
		CONFIRMED,
		/** it did not: Minecraft squads hunt this host as they hunt guests (until the next raise tests again) */
		FALLBACK
	}

	private State state = State.UNTESTED;
	private boolean fallback;
	private int need;
	private int expect;
	private long deadline;

	public State state() {
		return this.state;
	}

	/**
	 * A {@code heat} DOWN with {@code wanted} went to the host at {@code tick}, while its {@code stats.wanted} was {@code before}.
	 * Returns true when this starts a test (the caller logs it): a raise above {@code before}, not while one is running, not once
	 * confirmed.
	 */
	public boolean sent(final int wanted, final long tick, final int before) {
		if (this.state == State.CONFIRMED || this.state == State.PENDING || wanted <= Math.max(0, before)) {
			return false;
		}

		this.state = State.PENDING;
		this.expect = wanted;
		this.need = Math.min(wanted, Math.max(0, before) + 1);
		this.deadline = tick + WINDOW_TICKS;
		return true;
	}

	/**
	 * The host's latest {@code stats.wanted} at {@code tick}; returns true when the path changed or a test ended (the caller logs the
	 * one line saying which path is active).
	 */
	public boolean update(final int wanted, final long tick) {
		if (this.state != State.PENDING) {
			return false;
		}

		if (wanted >= this.need) {
			this.state = State.CONFIRMED;
			this.fallback = false;
			return true;
		}

		if (tick >= this.deadline) {
			this.state = State.FALLBACK;
			this.fallback = true;
			return true;
		}

		return false;
	}

	/** May Minecraft squads hunt this host? Only while the last test failed (a re-test keeps them on until it passes). */
	public boolean squadsHunt() {
		return this.fallback;
	}

	/** The wanted level of the last test. */
	public int expected() {
		return this.expect;
	}

	/** The wanted level the last test waits for. */
	public int needed() {
		return this.need;
	}
}
