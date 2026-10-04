package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.figure.VehicleModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: the 12 vehicle models in code on 5A's UV layout, their paint, the light bar in a chase, the dark wreck. */
class VehicleModelsTest {
	@Test
	void twelveModelsOfRealBoxes() {
		assertEquals(12, VehicleModel.values().length);
		Set<List<String>> shapes = new HashSet<>();
		for (VehicleModel m : VehicleModel.values()) {
			List<VehicleModels.Part> parts = VehicleModels.parts(m);
			assertFalse(parts.isEmpty(), m.toString());
			List<String> shape = new ArrayList<>();
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, top = 0;
			for (VehicleModels.Part p : parts) {
				assertTrue(p.w() > 0 && p.h() > 0 && p.d() > 0, m + ": " + p.name());
				for (int[] at : p.at()) {
					assertTrue(at[1] + p.h() <= 0, m + " " + p.name() + ": every box stands on or above the ground (y down)");
					minX = Math.min(minX, at[0]);
					maxX = Math.max(maxX, at[0] + p.w());
					minZ = Math.min(minZ, at[2]);
					maxZ = Math.max(maxZ, at[2] + p.d());
					top = Math.min(top, at[1]);
					shape.add(p.name() + java.util.Arrays.toString(at));
				}
			}

			assertEquals(0, minX + maxX, m + " is symmetric left and right");
			double length = (maxZ - minZ) * VehicleModels.SCALE / 16.0, height = -top * VehicleModels.SCALE / 16.0;
			assertTrue(length >= 2.0 && length <= 6.0, m + " is vehicle-long: " + length + " blocks");
			assertTrue(height >= 1.0 && height <= 3.5, m + " is vehicle-high: " + height + " blocks");
			if (m != VehicleModel.NCPD_AERODYNE) {
				assertTrue(shapes.add(shape), m + " has a shape of its own (only the two aerodynes share one)");
			}
		}
	}

	@Test
	void noTwoRegionsOfATextureOverlap() {
		for (VehicleModel m : VehicleModel.values()) {
			List<int[]> regions = new ArrayList<>();
			for (VehicleModels.Part p : VehicleModels.parts(m)) {
				// the vanilla unwrap: 2d + 2w wide, d + h high, from (u, v)
				int[] r = {p.u(), p.v(), p.u() + 2 * p.d() + 2 * p.w(), p.v() + p.d() + p.h()};
				assertTrue(r[2] <= VehicleModels.TEXTURE && r[3] <= VehicleModels.TEXTURE, m + " " + p.name() + " fits the 128 x 128 texture");
				for (int[] o : regions) {
					assertFalse(r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3], m + " " + p.name() + " overlaps another region");
				}

				regions.add(r);
			}
		}
	}

	@Test
	void emergencyVehiclesCarryALightBar() {
		for (VehicleModel m : VehicleModel.values()) {
			boolean bar = VehicleModels.parts(m).stream().anyMatch(p -> p.name().equals("light_bar"));
			assertEquals(m.siren(), bar, m.toString());
		}
	}

	@Test
	void theLightBarFlashesOnlyInAChase() {
		VehicleModel m = VehicleModel.POLICE_CRUISER;
		assertEquals(0, VehicleModels.flashing(m, true, false, 0.0F));
		assertEquals(1, VehicleModels.flashing(m, true, false, VehicleModels.FLASH_TICKS), "the halves take turns");
		assertEquals(0, VehicleModels.flashing(m, true, false, 2 * VehicleModels.FLASH_TICKS));
		assertEquals(-1, VehicleModels.flashing(m, false, false, 0.0F), "no chase, no flash");
		assertEquals(-1, VehicleModels.flashing(m, true, true, 0.0F), "a wreck does not flash");
		assertEquals(-1, VehicleModels.flashing(VehicleModel.TAXI, true, false, 0.0F), "a taxi has no light bar");
	}

	@Test
	void thePaintIsTheColourAndAWreckIsDark() {
		assertEquals(0xFF123456, VehicleModels.paint(0x123456, false));
		assertEquals(0xFF123456, VehicleModels.paint(0xFF123456, false));
		assertEquals(VehicleModels.WRECK, VehicleModels.paint(0x123456, true));
		assertEquals(0xFFFFFFFF, VehicleModels.detail(false));
		assertEquals(VehicleModels.WRECK, VehicleModels.detail(true));
	}
}
