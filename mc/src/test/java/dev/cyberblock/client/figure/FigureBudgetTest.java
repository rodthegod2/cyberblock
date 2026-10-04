package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: the nearest figures under the cap, and the distance past which they stand still. */
class FigureBudgetTest {
	@Test
	void theCapKeepsTheNearestAndCutsNothingUnderIt() {
		int[] ids = {10, 11, 12, 13, 14};
		double[] d = {400.0, 1.0, 25.0, 1.0, 9.0};
		assertNull(FigureBudget.nearest(ids, d, 5, 5), "five figures, cap five: all drawn");
		assertNull(FigureBudget.nearest(ids, d, 5, 16));
		assertArrayEquals(new int[] {11, 13, 14}, FigureBudget.nearest(ids, d, 5, 3), "ties by id");
		assertArrayEquals(new int[] {11}, FigureBudget.nearest(ids, d, 4, 1), "only the first n count");
	}

	@Test
	void farFiguresStandStill() {
		assertTrue(FigureBudget.animated(0.0));
		assertTrue(FigureBudget.animated(FigureBudget.LOD_DISTANCE * FigureBudget.LOD_DISTANCE));
		assertFalse(FigureBudget.animated(33.0 * 33.0));
	}
}
