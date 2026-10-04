package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.mode.match.PvpRules.Refusal;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: the PvP rule matrix (both toggles on, teammates) and the 15 s lock. */
class PvpRulesTest {
	@Test
	void matrix() {
		for (boolean attacker : new boolean[] {false, true}) {
			for (boolean victim : new boolean[] {false, true}) {
				for (boolean sameTeam : new boolean[] {false, true}) {
					for (boolean friendlyFire : new boolean[] {false, true}) {
						Refusal r = PvpRules.refusal(attacker, victim, sameTeam, friendlyFire);
						boolean applies = attacker && victim && (!sameTeam || friendlyFire);
						assertEquals(applies, r == null, "attacker " + attacker + " victim " + victim + " team " + sameTeam + " ff " + friendlyFire);
						if (!attacker) {
							assertEquals(Refusal.ATTACKER_OFF, r, "the attacker's own toggle is named first");
						} else if (!victim) {
							assertEquals(Refusal.VICTIM_OFF, r);
						} else if (sameTeam && !friendlyFire) {
							assertEquals(Refusal.TEAMMATE, r);
						}
					}
				}
			}
		}
	}

	@Test
	void onlyBothOnHits() {
		assertNull(PvpRules.refusal(true, true, false, false));
		assertNotNull(PvpRules.refusal(true, false, false, false));
		assertNotNull(PvpRules.refusal(false, true, false, false));
		assertNotNull(PvpRules.refusal(false, false, false, false));
	}

	@Test
	void fifteenSecondLock() {
		long hit = 1_000_000L;
		assertEquals(15_000L, PvpRules.lockLeft(hit, hit));
		assertEquals(1L, PvpRules.lockLeft(hit + 14_999L, hit));
		assertEquals(0L, PvpRules.lockLeft(hit + 15_000L, hit));
		assertEquals(0L, PvpRules.lockLeft(hit + 60_000L, hit));
		assertEquals(0L, PvpRules.lockLeft(hit, PvpRules.NEVER), "no hit yet: never locked");
		assertEquals(15, PvpRules.seconds(15_000L));
		assertEquals(1, PvpRules.seconds(1L), "the last millisecond still shows 1 s");
		assertEquals(0, PvpRules.seconds(0L));
	}

	@Test
	void turningOffWaitsForTheLock() {
		long hit = 5_000L;
		assertTrue(PvpRules.next(true, false, hit + 10_000L, hit), "10 s after a hit it stays on");
		assertFalse(PvpRules.next(true, false, hit + 15_000L, hit), "15 s after it may go off");
		assertTrue(PvpRules.next(false, true, hit + 1L, hit), "turning on is never locked");
		assertFalse(PvpRules.next(false, false, hit + 1L, hit), "off stays off");
		assertFalse(PvpRules.next(true, false, 0L, PvpRules.NEVER));
	}
}
