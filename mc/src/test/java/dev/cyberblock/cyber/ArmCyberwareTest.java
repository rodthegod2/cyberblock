package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.FlightRules;
import dev.cyberblock.client.cyber.ArmCyberware;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plan retest3 section 1.8 (F6, F11): what Steve's arm shows, and the L abilities' pure rules. */
class ArmCyberwareTest {
	private static final Set<Cyberware> MANTIS = EnumSet.of(Cyberware.MANTIS_BLADES, Cyberware.CYBERDECK);

	@Test
	void theInstalledArmsImplantIsDrawn() {
		ArmCyberware.Arm arm = ArmCyberware.armStack(MANTIS, true, false, false, false);
		assertSame(Cyberware.MANTIS_BLADES, arm.cyberware());
		assertEquals("arm", arm.model());
		for (Cyberware c : ArmCyberware.ARMS) {
			assertSame(c, ArmCyberware.armStack(EnumSet.of(c), false, false, false, false).cyberware());
			assertSame(CyberSlot.ARMS, c.slot());
		}

		assertNull(ArmCyberware.armStack(EnumSet.of(Cyberware.SANDEVISTAN, Cyberware.KIROSHI_OPTICS), true, false, false, true));
	}

	@Test
	void deployedOnlyWithAnEmptyHand() {
		assertEquals("arm_out", ArmCyberware.armStack(MANTIS, true, false, false, true).model());
		assertEquals("arm", ArmCyberware.armStack(MANTIS, false, false, false, true).model());
	}

	@Test
	void hiddenWithTheGunPoseAndInVehicles() {
		assertNull(ArmCyberware.armStack(MANTIS, true, true, false, false));
		assertNull(ArmCyberware.armStack(MANTIS, true, false, true, false));
	}

	@Test
	void theLungeCoversTheGapInAboutSixTicks() {
		double[] v = CyberwareRules.lungeVelocity(6.0, 0.0, 0.0);
		assertEquals(1.0, v[0], 1.0E-9);
		assertEquals(0.2, v[1], 1.0E-9);
		assertEquals(0.0, v[2], 1.0E-9);
		// far: capped at 2 blocks a tick; up or down a lot: clamped
		double[] far = CyberwareRules.lungeVelocity(0.0, 30.0, 20.0);
		assertEquals(2.0, far[2], 1.0E-9);
		assertEquals(1.0, far[1], 1.0E-9);
		assertEquals(-0.5, CyberwareRules.lungeVelocity(1.0, -30.0, 0.0)[1], 1.0E-9);
		double[] none = CyberwareRules.lungeVelocity(0.0, 0.0, 0.0);
		assertEquals(0.0, none[0]);
		assertEquals(0.0, none[2]);
	}

	@Test
	void theHostedLungeLandsAtOnce() {
		// the lunge's launch on flat ground is too low for the motion-launch filter, so V never carries Steve to the target...
		double[] v = CyberwareRules.lungeVelocity(8.0, 0.0, 0.0);
		assertFalse(FlightRules.launch(v[0], v[1], v[2], false));
		// ...so hosted it lands right away, at any reach; standalone only once he is there
		assertTrue(CyberwareRules.lungeLands(true, CyberwareRules.LUNGE_RANGE));
		assertFalse(CyberwareRules.lungeLands(false, 6.0));
		assertTrue(CyberwareRules.lungeLands(false, CyberwareRules.LUNGE_ARRIVE));
	}

	@Test
	void theLashIsA120DegreeArcOfFiveBlocks() {
		// looking along +z
		assertTrue(CyberwareRules.inLash(0.0, 1.0, 0.0, 0.0, 4.0));
		assertTrue(CyberwareRules.inLash(0.0, 1.0, 2.0, 0.0, 2.0)); // 45 degrees off
		assertTrue(CyberwareRules.inLash(0.0, 1.0, Math.sin(Math.toRadians(60.0)) * 3.0, 0.0, Math.cos(Math.toRadians(60.0)) * 3.0));
		assertFalse(CyberwareRules.inLash(0.0, 1.0, 3.0, 0.0, 1.0)); // about 72 degrees off
		assertFalse(CyberwareRules.inLash(0.0, 1.0, 0.0, 0.0, -3.0)); // behind
		assertFalse(CyberwareRules.inLash(0.0, 1.0, 0.0, 0.0, 5.5)); // too far
		assertTrue(CyberwareRules.inLash(0.0, 1.0, 0.1, -0.5, 0.1)); // at his feet
	}

	@Test
	void theArmsAbilitiesFireOnL() {
		for (Cyberware c : ArmCyberware.ARMS) {
			assertSame(CyberwareRules.Trigger.ARMS, CyberwareRules.Trigger.of(c), c.id());
		}

		assertEquals(120, Cyberware.MANTIS_BLADES.cooldownTicks());
		assertEquals(160, Cyberware.GORILLA_ARMS.cooldownTicks());
		assertEquals(100, Cyberware.MONOWIRE.cooldownTicks());
	}
}
