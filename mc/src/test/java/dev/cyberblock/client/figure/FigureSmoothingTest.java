package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Plan 2026-10-04 task 3-F1: figures at real speed, smoothed client-side. */
class FigureSmoothingTest {
	private static final long FRAME = 16_666_667L;

	@Test
	void theFirstFrameIsWhereTheGameHasIt() {
		assertArrayEquals(new double[] {1, 2, 3}, new FigureSmoothing().at(1, 2, 3, 5L), 0.0);
	}

	@Test
	void aSteadyWalkIsDrawnAtItsRealPlace() {
		FigureSmoothing s = new FigureSmoothing();
		double[] at = null;
		for (int i = 0; i <= 120; i++) {
			// 1.4 m/s, a walk
			at = s.at(1.4 * i * FRAME / 1.0E9, 64, 0, i * FRAME);
		}

		assertEquals(1.4 * 120 * FRAME / 1.0E9, at[0], 0.01, "after the speed settles there is no lag behind the hitbox");
	}

	@Test
	void aCorrectionIsEasedInNotJumped() {
		FigureSmoothing s = new FigureSmoothing();
		s.at(0, 64, 0, 0);
		s.at(0, 64, 0, FRAME);
		double[] jumped = s.at(1.0, 64, 0, 2 * FRAME);
		assertTrue(jumped[0] < 0.2, "the figure barely moves on the frame the server corrects it: " + jumped[0]);
		double[] later = null;
		for (int i = 3; i < 60; i++) {
			later = s.at(1.0, 64, 0, i * FRAME);
		}

		assertEquals(1.0, later[0], 0.02, "within a second it is where the server has it");
	}

	@Test
	void aTeleportIsJustThere() {
		FigureSmoothing s = new FigureSmoothing();
		s.at(0, 64, 0, 0);
		assertArrayEquals(new double[] {100, 64, 0}, s.at(100, 64, 0, FRAME), 1.0E-9);
	}

	@Test
	void theFigureNeverTrailsFar() {
		FigureSmoothing s = new FigureSmoothing();
		s.at(0, 64, 0, 0);
		double[] at = s.at(FigureSmoothing.SNAP - 0.01, 64, 0, FRAME);
		assertTrue(FigureSmoothing.SNAP - 0.01 - at[0] <= FigureSmoothing.MAX_OFFSET + 1.0E-9);
	}

	@Test
	void theSameFrameTwiceIsTheSamePlace() {
		FigureSmoothing s = new FigureSmoothing();
		s.at(0, 64, 0, 0);
		double[] a = s.at(0.5, 64, 0, FRAME);
		assertArrayEquals(a, s.at(0.5, 64, 0, FRAME), 0.0);
	}
}
