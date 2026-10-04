package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: the figures line counts each figure once a second and averages the seconds of its window. */
class FigureStatsTest {
	@Test
	void averagesPerSecondAndSaysTheCap() {
		FigureStats.Window w = new FigureStats.Window();
		for (int second = 0; second < 2; second++) {
			for (int frame = 0; frame < 30; frame++) {
				for (int id = 0; id < 10; id++) {
					w.near.add(id);
					if (id < 6 + second * 2) {
						w.drawn.add(id);
					}
				}

				w.animated.add(1);
			}

			w.sample(100 + second * 20);
		}

		assertEquals("figures: 7.0 drawn of 10.0 near (max 8, cap 24), 1.0 animated; client 110 fps", w.line(24));
		assertNull(w.line(24), "the window started over");
	}

	@Test
	void noFiguresNoLine() {
		FigureStats.Window w = new FigureStats.Window();
		w.sample(60);
		assertNull(w.line(0));
	}
}
