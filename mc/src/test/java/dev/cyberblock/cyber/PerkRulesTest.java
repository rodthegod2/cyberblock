package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerkRulesTest {
	@Test
	void doubleTapWithin300ms() {
		PerkRules.DoubleTap tap = new PerkRules.DoubleTap();
		assertFalse(tap.press(1000));
		assertTrue(tap.press(1300));
		assertFalse(tap.press(1400)); // a third press starts over
		assertFalse(tap.press(1800)); // 400 ms after the last: too slow
		assertTrue(tap.press(2000));
	}

	@Test
	void camoAfterTwoStillSecondsThenEverySecond() {
		PerkRules.Camo camo = new PerkRules.Camo();
		assertFalse(camo.update(true, 0, 64, 0, 0));
		assertFalse(camo.update(true, 0.05, 64, 0, 1999));
		assertTrue(camo.update(true, 0.05, 64, 0.05, 2000));
		assertFalse(camo.update(true, 0.05, 64, 0, 2500));
		assertTrue(camo.update(true, 0.05, 64, 0, 3000));
	}

	@Test
	void camoResetsOnMovingOrStanding() {
		PerkRules.Camo camo = new PerkRules.Camo();
		camo.update(true, 0, 64, 0, 0);
		assertFalse(camo.update(true, 0.1, 64, 0, 1500)); // moved 0.1 m from where it began: starts over here
		assertFalse(camo.update(true, 0.1, 64, 0, 3000));
		assertTrue(camo.update(true, 0.1, 64, 0, 3500));
		assertFalse(camo.update(false, 0.1, 64, 0, 3600)); // stood up
		assertFalse(camo.update(true, 0.1, 64, 0, 3700));
		assertFalse(camo.update(true, 0.1, 64, 0, 5600));
		assertTrue(camo.update(true, 0.1, 64, 0, 5700));
	}

	@Test
	void creepingSlowlyIsMoving() {
		PerkRules.Camo camo = new PerkRules.Camo();
		boolean given = false;
		for (int t = 0; t <= 100; t++) { // 0.004 m per tick: 0.1 m in 25 ticks
			given |= camo.update(true, t * 0.004, 64, 0, t * 50L);
		}

		assertFalse(given);
	}

	@Test
	void homingCone() {
		assertTrue(PerkRules.inCone(1, 0, 0, 10, 0, 0));
		assertTrue(PerkRules.inCone(1, 0, 0, 10, Math.tan(Math.toRadians(19)) * 10, 0));
		assertFalse(PerkRules.inCone(1, 0, 0, 10, Math.tan(Math.toRadians(21)) * 10, 0));
		assertFalse(PerkRules.inCone(1, 0, 0, -10, 0, 0)); // behind
		assertFalse(PerkRules.inCone(1, 0, 0, 33, 0, 0)); // out of range
		assertTrue(PerkRules.inCone(1, 0, 0, 32, 0, 0));
		assertFalse(PerkRules.inCone(0, 0, 0, 10, 0, 0)); // not moving
	}

	@Test
	void steeringTurnsAtMostSixDegreesAndKeepsSpeed() {
		double[] v = PerkRules.steer(3, 0, 0, 0, 0, 10); // target at 90 degrees
		assertEquals(3.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1.0E-9);
		assertEquals(6.0, Math.toDegrees(Math.atan2(v[2], v[0])), 1.0E-9);
		assertEquals(0.0, v[1], 1.0E-12);
		double[] close = PerkRules.steer(2, 0, 0, 10, 0.5, 0); // within 6 degrees: straight at it
		assertEquals(Math.toDegrees(Math.atan2(0.5, 10)), Math.toDegrees(Math.atan2(close[1], close[0])), 1.0E-9);
		assertArrayEquals(new double[]{0, 0, 0}, PerkRules.steer(0, 0, 0, 1, 0, 0));
	}

	@Test
	void multipliers() {
		assertEquals(20, PerkRules.bowTimeHeld(14)); // full draw after 14 ticks
		assertEquals(0, PerkRules.bowTimeHeld(0));
		assertEquals(18, PerkRules.crossbowCharge(25));
		assertEquals(1, PerkRules.crossbowCharge(1));
		assertEquals(2, PerkRules.craftedCount(1, 64));
		assertEquals(8, PerkRules.craftedCount(4, 64)); // arrows: 4 per craft become 8
		assertEquals(64, PerkRules.craftedCount(40, 64));
		assertEquals(6.0F, PerkRules.heal(4.0F));
		assertEquals(12.0F, PerkRules.heal(8.0F));
		assertEquals(3, PerkRules.extraNutrition(6)); // 6 + 3 = 9 = x1.5
		assertEquals(2, PerkRules.extraNutrition(4));
		assertEquals(3.6F, PerkRules.extraSaturation(7.2F), 1.0E-6F);
		assertEquals(0.4, PerkRules.visibility(0.8), 1.0E-12);
	}
}
