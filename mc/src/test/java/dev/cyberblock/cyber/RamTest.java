package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RamTest {
	@Test
	void maxNeedsADeck() {
		assertEquals(0, Ram.max(false, false, false));
		assertEquals(0, Ram.max(false, true, true));
		assertEquals(8, Ram.max(true, false, false));
		assertEquals(12, Ram.max(true, true, false));
	}

	/** Plan retest2 section 1.7: the RAM upgrade implant adds 4, on top of the extra_ram perk's 4. */
	@Test
	void ramUpgradeAddsUpWithExtraRam() {
		assertEquals(12, Ram.max(true, false, true));
		assertEquals(16, Ram.max(true, true, true));
	}

	/** hasDeck: an installed cyberdeck, or one carried while the Operating System slot is empty. */
	@Test
	void deckFromTheOsSlotOrTheBag() {
		assertTrue(Ram.hasDeck(true, false, false));
		assertTrue(Ram.hasDeck(true, false, true));
		assertTrue(Ram.hasDeck(false, true, true));
		assertFalse(Ram.hasDeck(false, false, true)); // a Sandevistan in the OS slot: the bag's deck does not count
		assertFalse(Ram.hasDeck(false, true, false));
	}

	@Test
	void regenOnePointEveryThreeSeconds() {
		int ram = 0;
		for (long t = 1; t <= 600; t++) {
			ram = Ram.tick(ram, 8, t, false);
		}

		assertEquals(8, ram); // 600 ticks = 30 s = 10 points, capped at 8
		ram = 0;
		for (long t = 1; t <= 180; t++) {
			ram = Ram.tick(ram, 8, t, false);
		}

		assertEquals(3, ram);
	}

	@Test
	void overclockRegeneratesTwiceAsFast() {
		int ram = 0;
		for (long t = 1; t <= 180; t++) {
			ram = Ram.tick(ram, 12, t, true);
		}

		assertEquals(6, ram);
	}

	@Test
	void capAppliesAtOnce() {
		assertEquals(8, Ram.tick(12, 8, 1, false)); // extra_ram lost
		assertEquals(0, Ram.tick(5, 0, 60, false)); // cyberdeck gone
		assertEquals(8, Ram.tick(8, 8, 60, false)); // full stays full
		assertEquals(0, Ram.tick(-3, 8, 1, false)); // never negative
	}

	@Test
	void shardAddsFourUpToMax() {
		assertEquals(4, Ram.shard(0, 8));
		assertEquals(8, Ram.shard(6, 8));
		assertEquals(12, Ram.shard(8, 12));
		assertEquals(0, Ram.shard(0, 0));
	}

	@Test
	void cheapHacksTakesOneOffButNeverBelowOne() {
		assertEquals(3, Ram.cost(Hack.SHORT_CIRCUIT, false));
		assertEquals(2, Ram.cost(Hack.SHORT_CIRCUIT, true));
		assertEquals(1, Ram.cost(Hack.PING, true));
		assertEquals(1, Ram.cost(Hack.DEVICE, true));
		assertEquals(7, Ram.cost(Hack.CYBERPSYCHOSIS, true));
		for (Hack h : Hack.values()) {
			assertEquals(Math.max(1, h.ram() - 1), Ram.cost(h, true), h.name());
			assertEquals(h.ram(), Ram.cost(h, false), h.name());
		}
	}
}
