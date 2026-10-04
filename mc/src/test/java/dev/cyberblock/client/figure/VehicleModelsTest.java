package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.figure.VehicleModel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: the 12 vehicle models in code, their paint, the police lights in a chase, the dark wreck. */
class VehicleModelsTest {
	private static double min(final List<VehicleModels.Part> parts, final java.util.function.ToDoubleFunction<VehicleModels.Part> f) {
		return parts.stream().mapToDouble(f).min().orElseThrow();
	}

	private static double max(final List<VehicleModels.Part> parts, final java.util.function.ToDoubleFunction<VehicleModels.Part> f) {
		return parts.stream().mapToDouble(f).max().orElseThrow();
	}

	@Test
	void twelveDistinctModelsOfRealBoxes() {
		assertEquals(12, VehicleModel.values().length);
		Set<List<VehicleModels.Part>> shapes = new HashSet<>();
		for (VehicleModel m : VehicleModel.values()) {
			List<VehicleModels.Part> parts = VehicleModels.parts(m);
			assertTrue(parts.size() >= 5, m + " is more than a box");
			parts.forEach(p -> assertTrue(p.x1() > p.x0() && p.y1() > p.y0() && p.z1() > p.z0(), m + ": no empty box: " + p));
			assertTrue(shapes.add(parts), m + " has a shape of its own");
			assertTrue(parts.stream().anyMatch(p -> p.role() == VehicleModels.Role.PAINT), m + " has paint for the host's colour");
			double length = max(parts, VehicleModels.Part::z1) - min(parts, VehicleModels.Part::z0);
			double width = max(parts, VehicleModels.Part::x1) - min(parts, VehicleModels.Part::x0);
			assertTrue(length >= 2.0 && length <= 8.0, m + " is vehicle-long: " + length);
			assertTrue(width >= 0.6 && width <= 4.0, m + " is vehicle-wide: " + width);
			assertEquals(0.0, min(parts, p -> p.x0()) + max(parts, p -> p.x1()), 1.0E-9, m + " is symmetric left and right");
			assertTrue(max(parts, VehicleModels.Part::z1) > -min(parts, VehicleModels.Part::z0) - 0.5, m + " is centred");
		}
	}

	@Test
	void carsStandOnWheelsAndAerodynesHover() {
		for (VehicleModel m : VehicleModel.values()) {
			List<VehicleModels.Part> parts = VehicleModels.parts(m);
			if (m.flies()) {
				assertTrue(min(parts, VehicleModels.Part::y0) > 0.3, m + " hovers");
				assertTrue(parts.stream().anyMatch(p -> p.role() == VehicleModels.Role.THRUSTER), m + " has thrusters");
				assertTrue(parts.stream().noneMatch(p -> p.role() == VehicleModels.Role.TYRE), m + " has no wheels");
			} else {
				assertEquals(0.0, min(parts, VehicleModels.Part::y0), 0.0, m + " stands on the ground");
				long wheels = parts.stream().filter(p -> p.role() == VehicleModels.Role.TYRE).count();
				assertTrue(m == VehicleModel.BIKE ? wheels == 2 : wheels >= 4, m + " wheels: " + wheels);
			}
		}
	}

	@Test
	void onlyPoliceCarryALightBar() {
		for (VehicleModel m : VehicleModel.values()) {
			boolean bar = VehicleModels.parts(m).stream().anyMatch(p -> p.role() == VehicleModels.Role.SIREN_RED)
				&& VehicleModels.parts(m).stream().anyMatch(p -> p.role() == VehicleModels.Role.SIREN_BLUE);
			assertEquals(m.siren(), bar, m.toString());
		}
	}

	@Test
	void theLightBarFlashesOnlyInAChase() {
		VehicleModel m = VehicleModel.POLICE;
		int red0 = VehicleModels.colour(VehicleModels.Role.SIREN_RED, m, 0, false, true, 0.0F);
		int blue0 = VehicleModels.colour(VehicleModels.Role.SIREN_BLUE, m, 0, false, true, 0.0F);
		int red1 = VehicleModels.colour(VehicleModels.Role.SIREN_RED, m, 0, false, true, VehicleModels.FLASH_TICKS);
		int blue1 = VehicleModels.colour(VehicleModels.Role.SIREN_BLUE, m, 0, false, true, VehicleModels.FLASH_TICKS);
		assertEquals(VehicleModels.SIREN_RED_ON, red0);
		assertEquals(VehicleModels.SIREN_BLUE_OFF, blue0);
		assertEquals(VehicleModels.SIREN_RED_OFF, red1, "red and blue take turns");
		assertEquals(VehicleModels.SIREN_BLUE_ON, blue1);
		assertTrue(VehicleModels.glows(VehicleModels.Role.SIREN_RED, m, false, true));
		for (float t = 0; t < 20; t++) {
			assertEquals(VehicleModels.SIREN_RED_OFF, VehicleModels.colour(VehicleModels.Role.SIREN_RED, m, 0, false, false, t), "no chase, no flash");
			assertEquals(VehicleModels.SIREN_BLUE_OFF, VehicleModels.colour(VehicleModels.Role.SIREN_BLUE, m, 0, false, false, t));
		}

		assertFalse(VehicleModels.glows(VehicleModels.Role.SIREN_RED, m, false, false));
	}

	@Test
	void thePaintIsTheColourGiven() {
		assertEquals(0xFF123456, VehicleModels.colour(VehicleModels.Role.PAINT, VehicleModel.SEDAN, 0x123456, false, false, 0.0F));
		assertEquals(0xFF123456, VehicleModels.colour(VehicleModels.Role.PAINT, VehicleModel.SEDAN, 0xFF123456, false, false, 0.0F));
	}

	@Test
	void aWreckIsAllDarkAndGlowsNowhere() {
		for (VehicleModel m : VehicleModel.values()) {
			for (VehicleModels.Part p : VehicleModels.parts(m)) {
				assertEquals(VehicleModels.WRECK, VehicleModels.colour(p.role(), m, 0xFFFFFF, true, true, 0.0F));
				assertFalse(VehicleModels.glows(p.role(), m, true, true));
			}
		}
	}

	@Test
	void lightsGlow() {
		assertTrue(VehicleModels.glows(VehicleModels.Role.HEADLIGHT, VehicleModel.SEDAN, false, false));
		assertTrue(VehicleModels.glows(VehicleModels.Role.THRUSTER, VehicleModel.TRAUMA_AV, false, false));
		assertTrue(VehicleModels.glows(VehicleModels.Role.ACCENT, VehicleModel.TAXI, false, false), "the taxi sign is lit");
		assertFalse(VehicleModels.glows(VehicleModels.Role.PAINT, VehicleModel.SEDAN, false, false));
	}
}
