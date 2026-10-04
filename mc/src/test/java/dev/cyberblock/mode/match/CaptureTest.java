package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.mode.match.Capture.Change;
import dev.cyberblock.mode.match.Capture.Point;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: Gang Wars capture points (stand in a zone to capture). */
class CaptureTest {
	private static Point point() {
		return new Point("Corner", 0.0, 64.0, 0.0, 6.0);
	}

	/** Steps of one second until something changes or {@code max} steps passed; the number of steps taken. */
	private static int until(final Point p, final int[] counts, final Change wanted, final int max) {
		for (int i = 1; i <= max; i++) {
			if (Capture.step(p, counts, 1.0, 10.0) == wanted) {
				return i;
			}
		}

		return -1;
	}

	@Test
	void oneTeamAloneCapturesInCaptureSeconds() {
		Point p = point();
		assertEquals(10, until(p, new int[] {1, 0}, Change.CAPTURED, 20));
		assertEquals(0, p.owner());
		assertEquals(1.0, p.progress(), 1e-9);
		assertEquals(Change.NONE, Capture.step(p, new int[] {1, 0}, 1.0, 10.0), "an owned point is captured once");
	}

	@Test
	void morePlayersCaptureFasterUpToThree() {
		Point two = point();
		assertEquals(5, until(two, new int[] {2, 0}, Change.CAPTURED, 20));
		Point five = point();
		assertEquals(4, until(five, new int[] {0, 5}, Change.CAPTURED, 20), "capped at three players: 10/3 s rounds up to 4 steps");
		assertEquals(1, five.owner());
	}

	@Test
	void contestedPointsDoNotMove() {
		Point p = point();
		Capture.step(p, new int[] {1, 0}, 3.0, 10.0);
		double before = p.progress();
		for (int i = 0; i < 30; i++) {
			assertEquals(Change.NONE, Capture.step(p, new int[] {2, 1}, 1.0, 10.0));
		}

		assertEquals(before, p.progress(), 1e-9);
		assertEquals(-1, p.owner());
	}

	@Test
	void anOwnedPointIsNeutralisedFirstThenTaken() {
		Point p = point();
		until(p, new int[] {1, 0}, Change.CAPTURED, 20);
		assertEquals(10, until(p, new int[] {0, 1}, Change.NEUTRALISED, 20), "the owner's full bar drains first");
		assertEquals(-1, p.owner());
		assertEquals(1, p.holder());
		assertEquals(10, until(p, new int[] {0, 1}, Change.CAPTURED, 20), "then the new team fills its own bar");
		assertEquals(1, p.owner());
	}

	@Test
	void theOwnerRefillsItsDrainedBar() {
		Point p = point();
		until(p, new int[] {1, 0}, Change.CAPTURED, 20);
		Capture.step(p, new int[] {0, 1}, 4.0, 10.0);
		assertEquals(0.6, p.progress(), 1e-9);
		assertEquals(Change.NONE, Capture.step(p, new int[] {0, 0}, 10.0, 10.0));
		assertEquals(0.6, p.progress(), 1e-9, "nobody there: an owned point keeps its bar");
		Capture.step(p, new int[] {1, 0}, 4.0, 10.0);
		assertEquals(1.0, p.progress(), 1e-9);
		assertEquals(0, p.owner());
	}

	@Test
	void anUnfinishedCaptureFadesAtHalfSpeed() {
		Point p = point();
		Capture.step(p, new int[] {1, 0}, 4.0, 10.0);
		assertEquals(0.4, p.progress(), 1e-9);
		Capture.step(p, new int[] {0, 0}, 4.0, 10.0);
		assertEquals(0.2, p.progress(), 1e-9);
		Capture.step(p, new int[] {0, 0}, 10.0, 10.0);
		assertEquals(0.0, p.progress(), 1e-9);
		assertEquals(-1, p.holder(), "an empty neutral bar belongs to nobody");
	}

	@Test
	void insideIsAHorizontalCircle() {
		Point p = point();
		assertTrue(p.inside(3.0, 4.0));
		assertTrue(p.inside(6.0, 0.0));
		assertFalse(p.inside(5.0, 5.0));
	}
}
