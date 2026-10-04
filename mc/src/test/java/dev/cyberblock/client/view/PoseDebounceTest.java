package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Megaplan Q-V2: a render flag changes only after the new value held for 2 ticks. */
class PoseDebounceTest {
	@Test
	void aOneTickBlipNeverShows() {
		PoseDebounce d = new PoseDebounce();
		d.force(true); // shield raised
		assertTrue(d.update(false, 10)); // one tick of "not using"
		assertTrue(d.update(false, 10)); // several frames in that tick
		assertTrue(d.update(true, 11)); // back
		assertTrue(d.update(false, 12));
		assertTrue(d.update(false, 13), "held one tick only");
		assertTrue(d.update(true, 14));
	}

	@Test
	void aChangeShowsAfterTwoTicks() {
		PoseDebounce d = new PoseDebounce();
		assertFalse(d.update(true, 100));
		assertFalse(d.update(true, 101));
		assertTrue(d.update(true, 102));
		assertTrue(d.update(true, 103));
		assertTrue(d.update(false, 104));
		assertTrue(d.update(false, 105));
		assertFalse(d.update(false, 106));
	}

	@Test
	void aBlipRestartsTheHold() {
		PoseDebounce d = new PoseDebounce();
		assertFalse(d.update(true, 0));
		assertFalse(d.update(false, 1)); // went back before it held
		assertFalse(d.update(true, 2));
		assertFalse(d.update(true, 3));
		assertTrue(d.update(true, 4));
	}

	@Test
	void forceTakesTheValueAtOnce() {
		PoseDebounce d = new PoseDebounce();
		d.update(true, 0);
		d.force(false);
		assertFalse(d.update(false, 1));
		d.force(true);
		assertTrue(d.update(true, 1));
	}
}
