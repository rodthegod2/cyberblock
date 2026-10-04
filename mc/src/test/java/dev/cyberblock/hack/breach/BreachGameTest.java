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
	void theTimerStartsAtTheFirstPickAndEndsTheBreach() {
		BreachGame g = new BreachGame(puzzle(), 0);
		assertEquals(-1, g.leftMs(5_000));
		assertFalse(g.expire(50_000), "no timer before the first pick (idle is 60 s)");
		assertTrue(g.pick(0, 50_000));
		assertEquals(20_000, g.leftMs(50_000));
		assertEquals(5_000, g.leftMs(65_000));
		assertFalse(g.pick(10, 70_000), "a pick after the deadline is refused");
		assertEquals(BreachGame.End.TIMEOUT, g.end());
		assertTrue(g.failed(), "picks, nothing uploaded");
		assertFalse(g.cancelled());
	}

	@Test
	void idleBeforeTheFirstPickCancels() {
		BreachGame g = new BreachGame(puzzle(), 1_000);
		assertFalse(g.expire(1_000 + BreachGame.IDLE_MS - 1));
		assertTrue(g.expire(1_000 + BreachGame.IDLE_MS));
		assertEquals(BreachGame.End.IDLE, g.end());
		assertTrue(g.cancelled());
		assertFalse(g.failed());
	}

	@Test
	void quittingBeforeAPickCancelsAfterAPickFails() {
		BreachGame early = new BreachGame(puzzle(), 0);
		early.quit();
		assertTrue(early.cancelled());
		assertFalse(early.failed());

		BreachGame late = new BreachGame(puzzle(), 0);
		late.pick(0, 0);
		late.quit();
		assertEquals(BreachGame.End.QUIT, late.end());
		assertTrue(late.failed());
		assertEquals(0, late.strength());

		BreachGame some = new BreachGame(puzzle(), 0);
		some.pick(1, 0);
		some.pick(21, 0);
		some.quit();
		assertFalse(some.failed(), "one daemon uploaded is a success");
		assertEquals(1, some.strength());
	}
}
