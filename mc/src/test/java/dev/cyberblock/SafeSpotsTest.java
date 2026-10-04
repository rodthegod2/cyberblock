package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Plan Q-B6: the void-fall rescue thresholds and the choice of spot. */
class SafeSpotsTest {
	private static final int MIN_Y = -64;

	@Test
	void aFallOfMoreThan16BlocksAfterABreakNearbyIsRescued() {
		assertFalse(SafeSpots.shouldRescue(64 - 16, 64, MIN_Y, true), "exactly 16 below");
		assertTrue(SafeSpots.shouldRescue(64 - 16.01, 64, MIN_Y, true));
		assertFalse(SafeSpots.shouldRescue(64 - 30, 64, MIN_Y, false), "no break nearby: a fall is a fall");
	}

	@Test
	void theVoidIsAlwaysRescued() {
		assertTrue(SafeSpots.shouldRescue(MIN_Y + 7.9, 64, MIN_Y, false));
		assertFalse(SafeSpots.shouldRescue(MIN_Y + 8, 64, MIN_Y, false));
	}

	@Test
	void aBreakCountsFor3SecondsAndWithin8Blocks() {
		BlockPos spot = new BlockPos(0, 64, 0);
		assertTrue(SafeSpots.nearbyAndRecent(spot, new BlockPos(0, 63, 0), 100, 160));
		assertFalse(SafeSpots.nearbyAndRecent(spot, new BlockPos(0, 63, 0), 100, 161), "more than 3 s ago");
		assertTrue(SafeSpots.nearbyAndRecent(spot, new BlockPos(8, 40, 0), 100, 100), "height does not matter");
		assertFalse(SafeSpots.nearbyAndRecent(spot, new BlockPos(6, 63, 6), 100, 100), "8.5 blocks away");
	}

	@Test
	void theNewestSpotThatStillHasGroundIsChosen() {
		SafeSpots.Track track = new SafeSpots.Track();
		BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(1, 64, 0), c = new BlockPos(2, 64, 0);
		track.stood(a);
		track.stood(b);
		track.stood(b);
		track.stood(c);
		assertEquals(c, track.newest());
		assertEquals(b, track.pick(p -> !p.equals(c)), "the block under the newest one was the one broken");
		assertEquals(a, track.pick(p -> p.equals(a)));
		assertNull(track.pick(p -> false));
	}

	@Test
	void onlyTheLastFewSpotsAreKept() {
		SafeSpots.Track track = new SafeSpots.Track();
		for (int x = 0; x < SafeSpots.KEEP_SPOTS + 5; x++) {
			track.stood(new BlockPos(x, 64, 0));
		}

		assertNull(track.pick(p -> p.getX() < 5));
		assertEquals(new BlockPos(5, 64, 0), track.pick(p -> p.getX() == 5));
	}
}
