package dev.cyberblock.hack.breach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One breach being played (megaplan 6-B1), pure: the server validates every pick here (no client trust). A pick must follow
 * {@link BreachRules#allowed} on a free cell; the timer starts at the first pick; a daemon is uploaded once the buffer holds its
 * sequence as a contiguous run. It ends when every daemon is uploaded, the buffer is full, the timer runs out, the player quits, or
 * nothing was picked for {@link #IDLE_MS}. Quitting (or idling) before the first pick cancels: no result, no alarm. Times are
 * milliseconds from any one clock.
 */
public final class BreachGame {
	public enum End {
		NONE, ALL_UPLOADED, BUFFER_FULL, TIMEOUT, QUIT, IDLE
	}

	/** An opened breach with no pick for this long closes (cancelled). */
	public static final long IDLE_MS = 60_000;
	private final BreachPuzzle puzzle;
	private final long openedMs;
	private final int[] picks;
	private final int[] codes;
	private final boolean[] used;
	private final boolean[] uploaded;
	private int count;
	private long deadlineMs = -1;
	private End end = End.NONE;

	public BreachGame(final BreachPuzzle puzzle, final long openedMs) {
		this.puzzle = puzzle;
		this.openedMs = openedMs;
		this.picks = new int[puzzle.buffer()];
		this.codes = new int[puzzle.buffer()];
		this.used = new boolean[puzzle.size() * puzzle.size()];
		this.uploaded = new boolean[puzzle.daemons().size()];
	}

	public BreachPuzzle puzzle() {
		return this.puzzle;
	}

	/** The pick, if it is legal now; false (nothing changes) for an illegal cell, a used cell, a full buffer or an ended breach. */
	public boolean pick(final int cell, final long nowMs) {
		if (this.end != End.NONE || this.expire(nowMs) || !BreachRules.allowed(this.puzzle.size(), this.picks, this.count, cell) || this.used[cell]) {
			return false;
		}

		if (this.count == 0) {
			this.deadlineMs = nowMs + this.puzzle.seconds() * 1000L;
		}

		this.picks[this.count] = cell;
		this.codes[this.count] = this.puzzle.code(cell);
		this.used[cell] = true;
		this.count++;
		boolean all = true;
		for (int i = 0; i < this.uploaded.length; i++) {
			this.uploaded[i] |= BreachRules.uploaded(this.codes, this.count, this.puzzle.daemons().get(i).seq());
			all &= this.uploaded[i];
		}

		if (all) {
			this.end = End.ALL_UPLOADED;
		} else if (this.count == this.puzzle.buffer()) {
			this.end = End.BUFFER_FULL;
		}

		return true;
	}

	/** Ends the breach when its timer ran out (or it idled before the first pick); true when it ended just now. */
	public boolean expire(final long nowMs) {
		if (this.end != End.NONE) {
			return false;
		}

		if (this.deadlineMs >= 0 ? nowMs >= this.deadlineMs : nowMs - this.openedMs >= IDLE_MS) {
			this.end = this.deadlineMs >= 0 ? End.TIMEOUT : End.IDLE;
			return true;
		}

		return false;
	}

	/** The player stops (Disconnect button, closing the screen, walking away, leaving the server). */
	public void quit() {
		if (this.end == End.NONE) {
			this.end = End.QUIT;
		}
	}

	public End end() {
		return this.end;
	}

	public boolean ended() {
		return this.end != End.NONE;
	}

	/** Ended before the first pick: nothing happens, in either game. */
	public boolean cancelled() {
		return this.ended() && this.count == 0;
	}

	/** Ended after at least one pick with no daemon uploaded: the alarm and Heat. */
	public boolean failed() {
		return this.ended() && !this.cancelled() && this.uploadedDaemons().isEmpty();
	}

	/** The uploaded daemons, in the puzzle's order. */
	public List<BreachPuzzle.Daemon> uploadedDaemons() {
		List<BreachPuzzle.Daemon> out = new ArrayList<>();
		for (int i = 0; i < this.uploaded.length; i++) {
			if (this.uploaded[i]) {
				out.add(this.puzzle.daemons().get(i));
			}
		}

		return out;
	}

	/** {@link BreachRules#strength} of what is uploaded so far. */
	public int strength() {
		return BreachRules.strength(this.uploadedDaemons().stream().map(d -> d.seq().length).toList());
	}

	/** Milliseconds left on the timer, or -1 before the first pick. */
	public long leftMs(final long nowMs) {
		return this.deadlineMs < 0 ? -1 : Math.max(0, this.deadlineMs - nowMs);
	}

	public int[] picks() {
		return Arrays.copyOf(this.picks, this.count);
	}

	public boolean[] uploaded() {
		return this.uploaded.clone();
	}
}
