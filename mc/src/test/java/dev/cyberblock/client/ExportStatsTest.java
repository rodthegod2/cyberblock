package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: the frame export's cost line. */
class ExportStatsTest {
	private static final long MS = 1_000_000L, S = 1_000_000_000L;

	@Test
	void reportsOnceAWindowThenStartsOver() {
		FrameExporter.ExportStats stats = new FrameExporter.ExportStats();
		String line = null;
		for (int i = 0; i <= 60; i++) {
			line = stats.add(960, 540, (10 + i % 2) * MS, 2 * MS, i * S / 60, S);
			if (i < 60) {
				assertNull(line);
			}
		}

		assertEquals("frame export: 960x540, 61.0 fps, readback 10.5 ms avg (p95 11.0 ms), copy 2.00 ms avg (p95 2.00 ms)", line);
		assertNull(stats.add(960, 540, MS, MS, S + 1, S), "a new window");
	}

	@Test
	void percentileAndAverageOfNothingAreZero() {
		assertEquals(0.0, FrameExporter.ExportStats.avgMs(new long[0], 0));
		assertEquals(0.0, FrameExporter.ExportStats.p95Ms(new long[0], 0));
		long[] v = new long[100];
		for (int i = 0; i < 100; i++) {
			v[i] = (i + 1) * MS;
		}

		assertEquals(95.0, FrameExporter.ExportStats.p95Ms(v, 100));
		assertEquals(50.5, FrameExporter.ExportStats.avgMs(v, 100), 1e-9);
	}
}
