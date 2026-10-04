package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Megaplan Q-V2: the elytra's opening while Flight moves Steve follows vanilla's dive rule. */
class ViewPosesTest {
	@Test
	void wingsAreFullyOpenUnlessDiving() {
		assertEquals(1.0F, ViewPoses.wingOpening(0.0, 1.5));
		assertEquals(1.0F, ViewPoses.wingOpening(0.4, 1.5), "climbing");
		assertEquals(1.0F, ViewPoses.wingOpening(0.0, 0.0), "standing still in the air");
		assertEquals(1.0F, ViewPoses.wingOpening(Double.NaN, 1.0));
	}

	@Test
	void aDiveFoldsThemAsVanillaDoes() {
		assertEquals(0.0F, ViewPoses.wingOpening(-2.0, 2.0), 1e-6, "straight down");
		// 45 degrees down: 1 - (sqrt(1/2))^1.5
		double v = Math.sqrt(0.5);
		assertEquals(1.0 - Math.pow(v, 1.5), ViewPoses.wingOpening(-v, 1.0), 1e-6);
	}
}
