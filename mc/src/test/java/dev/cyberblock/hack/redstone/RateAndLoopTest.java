package dev.cyberblock.hack.redstone;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Megaplan 6-R1: each block acts at most 2 times a second; more than 8 toggles of one device in 4 s freeze it for 30 s. */
class RateAndLoopTest {
	@Test
	void twoActionsASecondPerBlock() {
		RateLimit limit = new RateLimit();
		assertTrue(limit.allow(1L, 0));
		assertTrue(limit.allow(1L, 5));
		assertFalse(limit.allow(1L, 10), "a third in the same second");
		assertTrue(limit.allow(2L, 10), "another block has its own budget");
		assertFalse(limit.allow(1L, 19));
		assertTrue(limit.allow(1L, 20), "the first one left the window");
		assertTrue(limit.allow(1L, 25));
		assertFalse(limit.allow(1L, 26));
	}

	@Test
	void refusedActionsDoNotUseTheBudget() {
		RateLimit limit = new RateLimit();
		limit.allow(1L, 0);
		limit.allow(1L, 1);
		for (int t = 2; t < 20; t++) {
			assertFalse(limit.allow(1L, t));
		}

		assertTrue(limit.allow(1L, 20));
	}

	@Test
	void pruneForgetsQuietBlocks() {
		RateLimit limit = new RateLimit();
		limit.allow(1L, 0);
		limit.allow(2L, 15);
		limit.prune(25);
		assertEquals(1, limit.size());
	}

	@Test
	void eightTogglesInFourSecondsPassTheNinthFreezes() {
		LoopGuard guard = new LoopGuard();
		for (int i = 0; i < LoopGuard.LIMIT; i++) {
			assertEquals(LoopGuard.Verdict.OK, guard.toggle("door", i * 5L));
		}

		assertEquals(LoopGuard.Verdict.TRIPPED, guard.toggle("door", 45));
		assertTrue(guard.frozen("door", 46));
		assertEquals(LoopGuard.Verdict.FROZEN, guard.toggle("door", 100));
		assertEquals(LoopGuard.Verdict.OK, guard.toggle("light", 100), "another device is not frozen");
		assertTrue(guard.frozen("door", 45 + LoopGuard.FREEZE_TICKS - 1), "frozen for 30 s");
		assertEquals(LoopGuard.Verdict.OK, guard.toggle("door", 45 + LoopGuard.FREEZE_TICKS), "and free again after it");
	}

	@Test
	void slowTogglesNeverFreeze() {
		LoopGuard guard = new LoopGuard();
		// two relays on a clock: one toggle every half second is 8 in 4 s, never more
		for (int i = 0; i < 100; i++) {
			assertEquals(LoopGuard.Verdict.OK, guard.toggle("door", i * 10L), "toggle " + i);
		}
	}

	@Test
	void theWindowIsFourSeconds() {
		LoopGuard guard = new LoopGuard();
		for (int i = 0; i < LoopGuard.LIMIT; i++) {
			guard.toggle("door", i);
		}

		assertEquals(LoopGuard.Verdict.OK, guard.toggle("door", LoopGuard.WINDOW_TICKS), "the first toggle left the window");
		assertEquals(LoopGuard.Verdict.TRIPPED, guard.toggle("door", LoopGuard.WINDOW_TICKS), "a ninth within the same 4 s");
	}

	@Test
	void pruneDropsFinishedFreezes() {
		LoopGuard guard = new LoopGuard();
		for (int i = 0; i <= LoopGuard.LIMIT; i++) {
			guard.toggle("door", i);
		}

		guard.prune(LoopGuard.FREEZE_TICKS + 100);
		assertFalse(guard.frozen("door", LoopGuard.FREEZE_TICKS + 100));
	}
}
