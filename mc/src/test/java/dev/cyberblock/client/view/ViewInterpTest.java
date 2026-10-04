package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Megaplan Q-V1: Steve's render position between two cam samples, by receive time. */
class ViewInterpTest {
	private static final long MS = 1_000_000L;

	private static ViewInterp twoSamples() {
		ViewInterp v = new ViewInterp();
		v.sample(100 * MS, 0.0, 64.0, 0.0);
		v.sample(116 * MS, 1.6, 64.0, -0.8); // 16 ms later, moved 1.6 m in x
		return v;
	}

	@Test
	void nothingBeforeTheFirstSample() {
		ViewInterp v = new ViewInterp();
		assertTrue(Double.isNaN(v.at(0L)[0]));
		assertEquals(1.0, v.factor(0L));
		assertTrue(Double.isNaN(v.intervalMillis()));
	}

	@Test
	void oneSampleIsThatSampleAtAnyTime() {
		ViewInterp v = new ViewInterp();
		v.sample(100 * MS, 3.0, 70.0, -2.0);
		assertArrayEquals(new double[] {3.0, 70.0, -2.0}, v.at(500 * MS), 1e-12);
	}

	@Test
	void theFramesOwnPoseIsDrawnExactlyThere() {
		ViewInterp v = twoSamples();
		assertEquals(1.0, v.factor(116 * MS), 1e-12);
		assertArrayEquals(new double[] {1.6, 64.0, -0.8}, v.at(116 * MS), 1e-12);
		assertArrayEquals(new double[] {0.0, 64.0, 0.0}, v.at(100 * MS), 1e-12);
	}

	@Test
	void interpolatesByReceiveTime() {
		ViewInterp v = twoSamples();
		assertEquals(0.5, v.factor(108 * MS), 1e-12);
		assertArrayEquals(new double[] {0.8, 64.0, -0.4}, v.at(108 * MS), 1e-12);
		assertEquals(16.0, v.intervalMillis(), 1e-9);
	}

	@Test
	void extrapolatesAtMostFiftyMilliseconds() {
		ViewInterp v = twoSamples();
		// 8 ms past the newest: half an interval ahead
		assertEquals(1.5, v.factor(124 * MS), 1e-12);
		// 200 ms past: capped at 50 ms past the newest = 1 + 50/16
		double cap = 1.0 + 50.0 / 16.0;
		assertEquals(cap, v.factor(316 * MS), 1e-12);
		assertEquals(1.6 * cap, v.at(10_000 * MS)[0], 1e-9);
	}

	@Test
	void neverBeforeTheOlderSample() {
		assertEquals(0.0, twoSamples().factor(0L));
	}

	@Test
	void theSameArrivalTwiceIsOneSampleAndResetForgets() {
		ViewInterp v = twoSamples();
		v.sample(116 * MS, 99.0, 99.0, 99.0);
		assertArrayEquals(new double[] {1.6, 64.0, -0.8}, v.at(116 * MS), 1e-12, "a repeated arrival is ignored");
		v.reset();
		assertTrue(Double.isNaN(v.at(116 * MS)[0]));
	}

	@Test
	void samplesOutOfOrderAreDrawnAtTheNewestWithoutDividingByZero() {
		ViewInterp v = new ViewInterp();
		v.sample(9 * MS, 1.0, 2.0, 3.0);
		v.sample(7 * MS, 4.0, 5.0, 6.0);
		assertEquals(1.0, v.factor(8 * MS));
		assertArrayEquals(new double[] {4.0, 5.0, 6.0}, v.at(8 * MS), 1e-12);
	}
}
