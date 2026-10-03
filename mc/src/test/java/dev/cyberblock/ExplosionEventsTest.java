package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExplosionEventsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void tntCreeperAndChargedCreeperKeepTheirVanillaRadius() {
		assertEquals("{\"t\":\"explosion\",\"pos\":[1.500,64.000,-2.500],\"power\":4.000,\"fire\":false}",
			ExplosionEvents.message(ExplosionInteraction.TNT, 1.5, 64, -2.5, 4.0f, false));
		assertTrue(ExplosionEvents.message(ExplosionInteraction.MOB, 0, 0, 0, 3.0f, false).contains("\"power\":3.000"));
		assertTrue(ExplosionEvents.message(ExplosionInteraction.MOB, 0, 0, 0, 6.0f, false).contains("\"power\":6.000"));
	}

	@Test
	void fireIsPassedOn() {
		assertTrue(ExplosionEvents.message(ExplosionInteraction.MOB, 0, 0, 0, 1.0f, true).endsWith("\"fire\":true}"));
	}

	@Test
	void fireworkBurstIsOnePointFive() {
		assertEquals(1.5, ExplosionEvents.FIREWORK_POWER);
		assertTrue(ExplosionEvents.message(ExplosionInteraction.NONE, 0, 0, 0, ExplosionEvents.FIREWORK_POWER, false).contains("\"power\":1.500"));
	}

	@Test
	void windChargesAndWindBurstsAreNotBlasts() {
		assertFalse(ExplosionEvents.isBlast(ExplosionInteraction.TRIGGER));
		assertTrue(ExplosionEvents.isBlast(ExplosionInteraction.NONE));
		assertTrue(ExplosionEvents.isBlast(ExplosionInteraction.BLOCK));
	}

	/** Layer 5: a wind charge (radius 1.2) or a breeze's (3.0) pushes Night City at 12 m/s per block of radius, and blows nothing up. */
	@Test
	void windBurstsKnockInstead() {
		assertEquals("{\"t\":\"knock\",\"pos\":[1.000,64.500,-2.000],\"radius\":1.200,\"strength\":14.400}",
			ExplosionEvents.message(ExplosionInteraction.TRIGGER, 1.0, 64.5, -2.0, 1.2f, false));
		assertTrue(ExplosionEvents.message(ExplosionInteraction.TRIGGER, 0, 0, 0, 3.0f, false).contains("\"strength\":36.000"));
		// the protocol's limits: radius 0..16, strength 0..50
		assertTrue(ExplosionEvents.message(ExplosionInteraction.TRIGGER, 0, 0, 0, 30.0, false).endsWith("\"radius\":16.000,\"strength\":50.000}"));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TRIGGER, 0, 0, 0, 0.0, false));
	}

	@Test
	void demolitionsMakesStevesOwnHalfAsBigAgain() {
		assertEquals(6.0f, ExplosionEvents.radius(4.0f, true, true));
		assertEquals(4.0f, ExplosionEvents.radius(4.0f, true, false));
		assertEquals(4.0f, ExplosionEvents.radius(4.0f, false, true));
	}

	@Test
	void sizeAndPositionMustBeNumbers() {
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, 0, 0, 0, 0.0, false));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, 0, 0, 0, -1.0, false));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, 0, 0, 0, Double.NaN, false));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, 0, 0, 0, Double.POSITIVE_INFINITY, false));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, Double.NaN, 0, 0, 4.0, false));
		assertNull(ExplosionEvents.message(ExplosionInteraction.TNT, 0, Double.POSITIVE_INFINITY, 0, 4.0, false));
	}

	@Test
	void hugeCommandExplosionsAreCappedAtWhatTheHostAccepts() {
		assertTrue(ExplosionEvents.message(ExplosionInteraction.MOB, 0, 0, 0, 500.0, false).contains("\"power\":20.000"));
	}

	@Test
	void barriersResistBlastsAsInVanilla() {
		// the explosion mixin enforces this too; the vanilla number is what keeps ordinary explosions away from the ground
		assertTrue(Blocks.BARRIER.getExplosionResistance() >= 3_600_000.0f);
	}
}
