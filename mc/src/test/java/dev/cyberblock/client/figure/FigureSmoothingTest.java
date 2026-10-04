package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.ProxyDiff;
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

	/**
	 * Megaplan task 5-F1: the host's lists come at 10 Hz (here with +-20 ms of network jitter), the server runs each proxy on at the
	 * speed of the last two lists (ProxyDiff.Motion) every tick, the client sees the tick positions and draws at 60 fps. At a run and at
	 * city driving speed the drawn figure never goes backwards (no rubber-banding) and stays close to where the NPC really is.
	 */
	@Test
	void tenHertzListsDoNotRubberBand() {
		for (double speed : new double[] {1.4, 5.0, 15.0}) {
			java.util.Random random = new java.util.Random(7);
			FigureSmoothing s = new FigureSmoothing();
			ProxyDiff.Motion motion = null;
			long nextList = 0L, nextTick = 0L;
			double serverX = 0.0, lastDrawn = Double.NaN, worst = 0.0;
			for (long now = 0L; now < 10_000_000_000L; now += FRAME) {
				while (nextList <= now) {
					// sampled by the host at its own steady 10 Hz, received with jitter
					double sampledAt = Math.round(nextList / 1.0E8) * 0.1;
					ProxyDiff.Entry e = new ProxyDiff.Entry(1L, speed * sampledAt, 64, 0, 0.0F, ProxyDiff.Kind.HUMAN, false);
					motion = motion == null ? ProxyDiff.Motion.first(e, nextList) : motion.next(e, nextList);
					nextList = Math.round((sampledAt + 0.1) * 1.0E9) + (long) ((random.nextDouble() - 0.5) * 40_000_000L);
				}

				while (nextTick <= now) {
					serverX = motion.position(nextTick).x;
					nextTick += 50_000_000L;
				}

				double drawn = s.at(serverX, 64, 0, now)[0];
				if (now > 1_000_000_000L) {
					assertTrue(drawn >= lastDrawn - 1.0E-3, speed + " m/s: the figure went back from " + lastDrawn + " to " + drawn + " at " + now / 1.0E9 + " s");
					worst = Math.max(worst, Math.abs(drawn - speed * now / 1.0E9));
				}

				lastDrawn = drawn;
			}

			assertTrue(worst < 0.05 * speed + 0.3, speed + " m/s: drawn at most this far from the NPC: " + worst);
		}
	}

	@Test
	void theSmoothedSpeedIsTheWalk() {
		FigureSmoothing s = new FigureSmoothing();
		for (int i = 0; i <= 120; i++) {
			s.at(1.4 * i * FRAME / 1.0E9, 64, 0, i * FRAME);
		}

		assertEquals(1.4, s.speed(), 0.01);
	}

	@Test
	void theSameFrameTwiceIsTheSamePlace() {
		FigureSmoothing s = new FigureSmoothing();
		s.at(0, 64, 0, 0);
		double[] a = s.at(0.5, 64, 0, FRAME);
		assertArrayEquals(a, s.at(0.5, 64, 0, FRAME), 0.0);
	}
}
