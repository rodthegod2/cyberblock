package dev.cyberblock.client.mode.van;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VanClientRulesTest {
	private static VanWire.Target person(final int eid, final Vec3 p) {
		return new VanWire.Target(VanRules.Kind.PERSON, eid, null, 0L, "n" + eid, p, false, "", VanRules.Seen.DRONE);
	}

	@Test
	void lookMatchesMinecraftsAxes() {
		Vec3 south = VanAim.look(0.0F, 0.0F);
		assertEquals(1.0, south.z, 1e-9);
		Vec3 west = VanAim.look(90.0F, 0.0F);
		assertEquals(-1.0, west.x, 1e-9);
		assertEquals(-1.0, VanAim.look(0.0F, 90.0F).y, 1e-9);
	}

	@Test
	void pickTakesTheTargetNearestTheMiddleOfTheView() {
		Vec3 eye = new Vec3(0, 70, 0);
		VanWire.Target ahead = person(1, new Vec3(0.5, 70, 10));
		VanWire.Target side = person(2, new Vec3(3, 70, 10));
		VanWire.Target behind = person(3, new Vec3(0, 70, -10));
		VanWire.Target far = person(4, new Vec3(0, 70, VanRules.DRONE_SIGHT + 1));
		List<VanWire.Target> all = List.of(side, behind, far, ahead);
		assertEquals(ahead, VanAim.pick(all, eye, VanAim.look(0, 0), VanWire.Target::p));
		// nothing within the cone: no pick
		assertNull(VanAim.pick(List.of(side, behind, far), eye, VanAim.look(0, 0), VanWire.Target::p));
		// the live entity's place wins over the state's
		assertEquals(behind, VanAim.pick(List.of(behind), eye, VanAim.look(0, 0), t -> new Vec3(0, 70, 5)));
	}

	@Test
	void miniDetailKeepsKnownColumnsOnly() {
		HoloWire.Detail d = VanMini.detail(10, 20, 3, (x, z) -> x == 11 && z == 21 ? null : x == 12 ? new int[] {70, 0} : new int[] {64 + z - 20, 29});
		assertEquals(10, d.x0());
		assertEquals(20, d.z0());
		assertEquals(HoloWire.UNKNOWN, d.heights()[d.index(11, 21)]);
		// colour 0 (no map colour) is unknown too: the shared map shows there
		assertEquals(HoloWire.UNKNOWN, d.heights()[d.index(12, 20)]);
		assertEquals(66, d.heights()[d.index(10, 22)]);
		assertEquals(29, d.colours()[d.index(10, 22)]);
	}

	@Test
	void wheelSlotsNeverOverlapAndClicksFindThem() {
		// the smallest GUI Minecraft picks (320 x 240) and a 1080p screen at GUI scale 4
		for (int[] screen : new int[][] {{320, 240}, {480, 270}, {960, 540}}) {
			int w = screen[0], h = screen[1];
			for (int n = 1; n <= 16; n++) {
				for (int i = 0; i < n; i++) {
					int[] a = VanWheelScreen.slot(i, n, w, h);
					assertTrue(a[0] - VanWheelScreen.SLOT_W / 2 >= 0 && a[0] + VanWheelScreen.SLOT_W / 2 <= w, "slot " + i + " of " + n + " inside " + w);
					assertTrue(a[1] - VanWheelScreen.SLOT_H / 2 >= 0 && a[1] + VanWheelScreen.SLOT_H / 2 <= h - 20, "slot " + i + " of " + n + " above the hint");
					assertEquals(i, VanWheelScreen.at(a[0], a[1], n, w, h), "a click on slot " + i + " of " + n);
					for (int j = i + 1; j < n; j++) {
						int[] b = VanWheelScreen.slot(j, n, w, h);
						boolean apart = Math.abs(a[0] - b[0]) >= VanWheelScreen.SLOT_W || Math.abs(a[1] - b[1]) >= VanWheelScreen.SLOT_H;
						assertTrue(apart, "slots " + i + " and " + j + " of " + n + " on " + w + "x" + h);
					}
				}
			}
		}

		// the middle picks nothing
		assertEquals(-1, VanWheelScreen.at(240, 135, 6, 480, 270));
		assertEquals(-1, VanWheelScreen.at(240, 135, 13, 480, 270));
	}

	@Test
	void droneEyeFollowsTheBattery() {
		assertEquals(0xFF40FF60, DroneRenderer.eyeColour(0.9F));
		assertEquals(0xFFFFD030, DroneRenderer.eyeColour(0.3F));
		assertEquals(0xFFFF3030, DroneRenderer.eyeColour(0.1F));
	}
}
