package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan Q-U2: the loading cover's dots and tip rotation. */
class LoadingCoverTest {
	private static final long MS = 1_000_000L;
	private static final long S = 1_000_000_000L;

	@Test
	void dotsCycleThroughFourStatesEveryFortyHundredthsOfASecond() {
		assertEquals(0, LoadingCover.dots(0L));
		assertEquals(0, LoadingCover.dots(399 * MS));
		assertEquals(1, LoadingCover.dots(400 * MS));
		assertEquals(3, LoadingCover.dots(1_200 * MS));
		assertEquals(0, LoadingCover.dots(1_600 * MS));
		assertEquals(3, LoadingCover.dots(-1L), "a negative clock still lands in range");
	}

	@Test
	void tipsAdvanceEverySixSecondsAndWrap() {
		long since = 10 * S;
		int first = LoadingCover.tipIndex(since, since, 8);
		assertEquals((first + 1) % 8, LoadingCover.tipIndex(since, since + 6 * S, 8));
		assertEquals(first, LoadingCover.tipIndex(since, since + 5 * S, 8));
		assertEquals(first, LoadingCover.tipIndex(since, since + 48 * S, 8), "eight tips wrap after 48 s");
		assertEquals(first, LoadingCover.tipIndex(since, since - S, 8), "a clock that went back shows the first tip");
	}

	@Test
	void everyIndexIsInRangeAndLoadsStartOnDifferentTips() {
		Set<Integer> starts = new HashSet<>();
		for (long since = 1; since < 40 * S; since += 997 * MS) {
			int i = LoadingCover.tipIndex(since, since, 8);
			assertTrue(i >= 0 && i < 8);
			starts.add(i);
		}

		assertTrue(starts.size() > 3, "the first tip varies between loads");
	}

	@Test
	void noTipsMeansNoTip() {
		assertEquals(-1, LoadingCover.tipIndex(S, 2 * S, 0));
		assertEquals(-1, LoadingCover.tipIndex(S, 2 * S, -3));
	}

	@Test
	void aQuietSpellStartsANewLoad() {
		long a = 5 * S;
		assertEquals(a, LoadingCover.since(a));
		assertEquals(a, LoadingCover.since(a + 500 * MS), "frames close together are one load");
		assertEquals(a, LoadingCover.since(a + 1_400 * MS));
		long b = a + 10 * S;
		assertEquals(b, LoadingCover.since(b), "10 s without a frame: a new load");
		assertEquals(b, LoadingCover.since(b + 100 * MS));
	}
}
