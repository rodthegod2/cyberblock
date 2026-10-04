package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.hack.breach.BreachPuzzle.Daemon;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 6-B1: the server's validation of every pick (no client trust), the timer, the ends, and what counts as a failure. */
class BreachGameTest {
	/**
	 * 5x5, buffer 4, codes = row index for every cell except cell 1 (top row, column 1) = 5 and cell 21 (row 4, column 1) = 3.
	 * Daemon A = [5, 3] (cell 1 then cell 21), daemon B = [3, 4, 2].
	 */
	static BreachPuzzle puzzle() {
		int[] grid = new int[25];
		for (int cell = 0; cell < 25; cell++) {
			grid[cell] = cell / 5;
		}

		grid[1] = 5;
		grid[21] = 3;
		return new BreachPuzzle(5, grid, 4, List.of(new Daemon("open_door", new int[] {5, 3}), new Daemon("overload", new int[] {3, 4, 2})),
			20, new int[] {1, 21, 22, 12});
	}

	@Test
	void firstPickIsInTheTopRow() {
		BreachGame g = new BreachGame(puzzle(), 0);
		assertFalse(g.pick(5, 10), "row 1");
		assertFalse(g.pick(-1, 10));
		assertFalse(g.pick(25, 10));
		assertEquals(0, g.picks().length);
		assertTrue(g.pick(3, 10));
	}

	@Test
	void picksAlternateColumnAndRowAndNeverRepeat() {
		BreachGame g = new BreachGame(puzzle(), 0);
		assertTrue(g.pick(1, 0)); // row 0, column 1
		assertFalse(g.pick(2, 0), "second pick must be in column 1, not row 0");
		assertFalse(g.pick(1, 0), "cell already used");
		assertTrue(g.pick(16, 0)); // column 1, row 3
		assertFalse(g.pick(21, 0), "third pick must be in row 3");
		assertTrue(g.pick(19, 0)); // row 3, column 4
		assertTrue(g.pick(4, 0)); // column 4, row 0
		assertArrayEquals(new int[] {1, 16, 19, 4}, g.picks());
		assertEquals(BreachGame.End.BUFFER_FULL, g.end());
		assertFalse(g.pick(0, 0), "no pick after the end");
	}

	@Test
	void daemonsUploadAsContiguousRuns() {
		BreachGame g = new BreachGame(puzzle(), 0);
		g.pick(1, 0); // 5
		g.pick(21, 0); // 3 -> A
		assertArrayEquals(new boolean[] {true, false}, g.uploaded());
		g.pick(22, 0); // row 4 -> 4
		g.pick(12, 0); // column 2 -> 2 -> B (3 4 2)
		assertArrayEquals(new boolean[] {true, true}, g.uploaded());
		assertEquals(BreachGame.End.ALL_UPLOADED, g.end());
		assertFalse(g.failed());
		assertEquals(2, g.strength(), "2 + 3 codes");
		assertEquals(List.of("open_door", "overload"), g.uploadedDaemons().stream().map(Daemon::id).toList());
	}

	@Test
	void theTimerStartsWhenTheBreachOpensAndEndsIt() {
		BreachGame g = new BreachGame(puzzle(), 1_000);
		assertEquals(20_000, g.leftMs(1_000), "protocol.md: the timer starts when the screen opens");
		assertEquals(5_000, g.leftMs(16_000), "studying the grid costs time too");
		assertTrue(g.pick(0, 16_000));
		assertEquals(5_000, g.leftMs(16_000), "a pick does not restart it");
		assertFalse(g.pick(10, 21_000), "a pick after the deadline is refused");
		assertEquals(BreachGame.End.TIMEOUT, g.end());
		assertEquals(0, g.leftMs(30_000));
		assertTrue(g.failed(), "picks, nothing uploaded");
		assertFalse(g.cancelled());
	}

	@Test
	void runningOutOfTimeBeforeAPickIsAFailure() {
		BreachGame g = new BreachGame(puzzle(), 0);
		assertFalse(g.expire(19_999));
		assertTrue(g.expire(20_000));
		assertFalse(g.expire(20_001), "ended once");
		assertEquals(BreachGame.End.TIMEOUT, g.end());
		assertFalse(g.started());
		assertFalse(g.cancelled(), "only a quit before a pick cancels");
		assertTrue(g.failed(), "a kept device breach that runs out is a failure");
	}

	@Test
	void quittingBeforeAPickCancelsAfterAPickFailsWhateverWasUploaded() {
		BreachGame early = new BreachGame(puzzle(), 0);
		early.quit();
		assertTrue(early.cancelled());
		assertFalse(early.failed());

		BreachGame late = new BreachGame(puzzle(), 0);
		late.pick(0, 0);
		assertTrue(late.started());
		late.quit();
		assertEquals(BreachGame.End.QUIT, late.end());
		assertTrue(late.failed());
		assertEquals(0, late.strength());

		BreachGame some = new BreachGame(puzzle(), 0);
		some.pick(1, 0);
		some.pick(21, 0);
		some.quit();
		assertTrue(some.failed(), "protocol.md: an abort after the first pick is a failure, even with a daemon uploaded");
		assertFalse(some.cancelled());
	}

	@Test
	void anUploadIsKeptWhenTheBufferFillsOrTheTimerRunsOut() {
		BreachGame full = new BreachGame(puzzle(), 0);
		full.pick(1, 0); // 5
		full.pick(21, 0); // 3 -> A
		full.pick(20, 0); // row 4 -> 4
		full.pick(0, 0); // column 0 -> 0, the buffer is full
		assertEquals(BreachGame.End.BUFFER_FULL, full.end());
		assertFalse(full.failed());
		assertEquals(1, full.strength());

		BreachGame late = new BreachGame(puzzle(), 0);
		late.pick(1, 0);
		late.pick(21, 0);
		late.expire(20_000);
		assertEquals(BreachGame.End.TIMEOUT, late.end());
		assertFalse(late.failed(), "the daemon was uploaded before the time ran out");
	}
}
