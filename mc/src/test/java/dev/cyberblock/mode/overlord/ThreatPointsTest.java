package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Megaplan 8-O1: Threat Points regenerate per intensity up to a bank, spawns and events spend them. */
class ThreatPointsTest {
	@Test
	void regenerationFollowsTheIntensity() {
		ThreatPoints chill = new ThreatPoints(0), normal = new ThreatPoints(0), brutal = new ThreatPoints(0);
		for (int s = 0; s < 10; s++) {
			chill.regen(1.0, Intensity.CHILL);
			normal.regen(1.0, Intensity.NORMAL);
			brutal.regen(1.0, Intensity.BRUTAL);
		}

		assertEquals(10, chill.whole());
		assertEquals(20, normal.whole());
		assertEquals(40, brutal.whole());
	}

	@Test
	void theBankIsCapped() {
		ThreatPoints tp = new ThreatPoints(0);
		tp.regen(1000.0, Intensity.NORMAL);
		assertEquals(Intensity.NORMAL.maxPoints(), tp.whole());
		tp.regen(0.0, Intensity.CHILL);
		assertEquals(Intensity.CHILL.maxPoints(), tp.whole(), "a lower intensity trims the bank");
	}

	@Test
	void badTimeAddsNothing() {
		ThreatPoints tp = new ThreatPoints(5);
		tp.regen(-3.0, Intensity.NORMAL);
		tp.regen(Double.NaN, Intensity.NORMAL);
		assertEquals(5, tp.whole());
		assertEquals(0, new ThreatPoints(-4).whole());
	}

	@Test
	void spendingNeedsEnoughPoints() {
		ThreatPoints tp = new ThreatPoints(30);
		assertTrue(tp.canAfford(30));
		assertFalse(tp.canAfford(31));
		assertFalse(tp.spend(31));
		assertEquals(30, tp.whole(), "a refused spend takes nothing");
		assertTrue(tp.spend(12));
		assertEquals(18, tp.whole());
		assertFalse(tp.canAfford(-1));
	}

	@Test
	void aCancelledSpawnIsRefundedUpToTheBank() {
		ThreatPoints tp = new ThreatPoints(110);
		tp.refund(20, Intensity.NORMAL);
		assertEquals(120, tp.whole());
		tp.refund(-5, Intensity.NORMAL);
		assertEquals(120, tp.whole());
	}

	@Test
	void theDearerSpawnTakesLongerToSaveFor() {
		// one entry is one spawn action: a 40-point squad needs twice the Normal regeneration time of a 20-point one
		double secondsFor20 = 20 / Intensity.NORMAL.regen(), secondsFor40 = 40 / Intensity.NORMAL.regen();
		assertEquals(2.0 * secondsFor20, secondsFor40);
		ThreatPoints tp = new ThreatPoints(0);
		tp.regen(secondsFor20, Intensity.NORMAL);
		assertTrue(tp.canAfford(20));
		assertFalse(tp.canAfford(40));
	}
}
