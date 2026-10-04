package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The photo feed's written fallback (megaplan 6-D1): the replica from above, when the DLL sends no picture. */
class ReplicaFeedTest {
	private static final int SIDE = ActWire.FEED_SIDE;

	@Test
	void standsInAfterThreeSecondsWithoutAPicture() {
		assertFalse(ReplicaFeed.due(1_000L, Long.MIN_VALUE, 3_999L), "the DLL has 3 s to send its first picture");
		assertTrue(ReplicaFeed.due(1_000L, Long.MIN_VALUE, 4_000L));
		assertFalse(ReplicaFeed.due(1_000L, 5_000L, 6_000L), "pictures arrive: no fallback");
		assertTrue(ReplicaFeed.due(1_000L, 5_000L, 8_000L), "pictures stopped for 3 s");
		assertFalse(ReplicaFeed.due(10_000L, 5_000L, 12_000L), "a new request waits 3 s again");
	}

	@Test
	void shadesLikeAMapItem() {
		int[] colours = new int[SIDE * SIDE], heights = new int[SIDE * SIDE];
		// column (0, 0) and its southern neighbours: grass (1) at 64, then 65 (higher: bright), 65 (flat), 63 (lower: dark)
		int[] ys = {64, 65, 65, 63};
		for (int z = 0; z < ys.length; z++) {
			colours[z * SIDE] = 1;
			heights[z * SIDE] = ys[z];
		}

		byte[] px = ReplicaFeed.shade(colours, heights);
		assertEquals(ActWire.FEED_BYTES, px.length);
		assertEquals(1 * 4 + 1, px[0], "the top row has no northern neighbour: normal");
		assertEquals(1 * 4 + 2, px[SIDE], "higher than the north: bright");
		assertEquals(1 * 4 + 1, px[2 * SIDE], "as high as the north: normal");
		assertEquals(1 * 4 + 0, px[3 * SIDE], "lower than the north: dark");
		assertEquals(0, px[1], "nothing there (the void) stays transparent");
	}
}
