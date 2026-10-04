package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.figure.FigureAnim.Gait;
import dev.cyberblock.figure.FigureTags;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: idle, walk and run from the host's bits or from speed; the ragdoll's tumble; the recoil. */
class FigureAnimTest {
	@Test
	void theHostsBitsWin() {
		assertEquals(Gait.RUN, FigureAnim.gait(FigureTags.RUN, 0.0));
		assertEquals(Gait.WALK, FigureAnim.gait(FigureTags.WALK, 5.0));
		assertEquals(Gait.RUN, FigureAnim.gait(FigureTags.WALK | FigureTags.RUN, 1.0));
		assertTrue(FigureAnim.fromBits(FigureTags.WALK));
		assertTrue(FigureAnim.fromBits(FigureTags.RUN | FigureTags.AIM));
		assertFalse(FigureAnim.fromBits(FigureTags.AIM | FigureTags.FIRE | FigureTags.COMBAT));
	}

	@Test
	void withoutBitsTheSpeedDecides() {
		assertEquals(Gait.IDLE, FigureAnim.gait(0, 0.0));
		assertEquals(Gait.IDLE, FigureAnim.gait(FigureTags.AIM, 0.1), "the smoothing's drift is no walk");
		assertEquals(Gait.WALK, FigureAnim.gait(0, 1.4));
		assertEquals(Gait.RUN, FigureAnim.gait(0, 5.0));
	}

	@Test
	void runningSwingsWiderAndFasterThanWalking() {
		assertEquals(0.0F, FigureAnim.swing(Gait.IDLE, 0.0));
		assertEquals(0.0F, FigureAnim.strideRate(Gait.IDLE, 0.0));
		assertTrue(FigureAnim.swing(Gait.RUN, 5.0) > FigureAnim.swing(Gait.WALK, 1.4));
		assertTrue(FigureAnim.strideRate(Gait.RUN, 5.0) > FigureAnim.strideRate(Gait.WALK, 1.4));
		assertTrue(FigureAnim.swing(Gait.WALK, 0.0) > 0.0F, "a walk the host reports while standing still still swings");
		// about one stride a second at a walk: vanilla's stride is 2 pi / 0.6662 units
		double strides = FigureAnim.strideRate(Gait.WALK, 1.4) / (2.0 * Math.PI / 0.6662);
		assertTrue(strides > 0.8 && strides < 1.3, "strides per second at 1.4 m/s: " + strides);
	}

	@Test
	void aRagdollFallsFlatAndSettles() {
		assertEquals(0.0F, FigureAnim.ragdollTilt(0.0));
		float previous = 0.0F;
		for (double t = 0.05; t < FigureAnim.RAGDOLL_FALL; t += 0.05) {
			float tilt = FigureAnim.ragdollTilt(t);
			assertTrue(tilt > previous, "it falls further at " + t);
			previous = tilt;
		}

		for (double t = FigureAnim.RAGDOLL_FALL; t < 5.0; t += 0.01) {
			float tilt = FigureAnim.ragdollTilt(t);
			assertTrue(tilt >= 80.0F && tilt <= 100.0F, "lying, give or take a bounce, at " + t + ": " + tilt);
		}

		assertEquals(90.0F, FigureAnim.ragdollTilt(3.0), 0.5F, "settled flat");
	}

	@Test
	void eachShotKicksOnce() {
		assertTrue(FigureAnim.recoil(0.0F) > 0.3F);
		assertEquals(0.0F, FigureAnim.recoil(FigureAnim.FIRE_PERIOD * 0.5F));
		assertEquals(FigureAnim.recoil(1.0F), FigureAnim.recoil(1.0F + FigureAnim.FIRE_PERIOD), 1.0E-6F);
	}
}
