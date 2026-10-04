package dev.cyberblock.hack.breach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One breach being played (megaplan 6-B1), pure: the server validates every pick here (no client trust). A pick must follow
 * {@link BreachRules#allowed} on a free cell; the timer starts when the breach opens (protocol.md "Phase 6"); a daemon is uploaded once
 * the buffer holds its sequence as a contiguous run. It ends when every daemon is uploaded, the buffer is full, the timer runs out or the
 * player quits. Quitting before the first pick cancels (no result, no alarm); quitting after it is a failure, whatever was uploaded.
 * A device breach is not quit before its first pick: {@link Breach} keeps it running instead (an abort is not a free re-roll). Times
 * are milliseconds from any one clock.
 */
public final class BreachGame {
	public enum End {
		NONE, ALL_UPLOADED, BUFFER_FULL, TIMEOUT, QUIT
	}

	private final BreachPuzzle puzzle;
	private final long deadlineMs;
	private final int[] picks;
	private final int[] codes;
	private final boolean[] used;
	private final boolean[] uploaded;
	private int count;
	private End end = End.NONE;

	public BreachGame(final BreachPuzzle puzzle, final long openedMs) {
		this.puzzle = puzzle;
		this.deadlineMs = openedMs + puzzle.seconds() * 1000L;
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

	/** Ends the breach when its timer ran out; true when it ended just now. */
	public boolean expire(final long nowMs) {
		if (this.end == End.NONE && nowMs >= this.deadlineMs) {
			this.end = End.TIMEOUT;
			return true;
		}

		return false;
	}

	/** The player stops (Disconnect button, closing the screen, walking away, leaving the server, the opener closing it). */
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

	/** Has the player picked anything yet? */
	public boolean started() {
		return this.count > 0;
	}

	/** Quit before the first pick: nothing happens, in either game. */
	public boolean cancelled() {
		return this.end == End.QUIT && this.count == 0;
	}

	/**
	 * The alarm and Heat: quit after the first pick (even with a daemon uploaded), or ended with no daemon uploaded (the buffer full,
	 * the timer, a kept device breach whose timer ran out before a pick).
	 */
	public boolean failed() {
		return this.ended() && !this.cancelled() && (this.end == End.QUIT || this.uploadedDaemons().isEmpty());
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

	/** Milliseconds left on the timer (it runs from the opening). */
	public long leftMs(final long nowMs) {
		return Math.max(0, this.deadlineMs - nowMs);
	}

	public int[] picks() {
		return Arrays.copyOf(this.picks, this.count);
	}

	public boolean[] uploaded() {
		return this.uploaded.clone();
	}
}
