package dev.cyberblock.mode.van;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.hack.actions.RemoteHack;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VanRulesTest {
	private static final VanRules.Keys FORWARD = new VanRules.Keys(true, false, false, false, false, false);

	@Test
	void linkRangeIsHorizontal() {
		Vec3 van = new Vec3(0, 64, 0);
		assertTrue(VanRules.links(van, new Vec3(512, 300, 0)));
		assertFalse(VanRules.links(van, new Vec3(400, 64, 400)));
	}

	@Test
	void areaIsTheHostsSurroundings() {
		Vec3 v = new Vec3(100, 70, 100);
		assertTrue(VanRules.inArea(new Vec3(148, 10, 100), v));
		assertFalse(VanRules.inArea(new Vec3(149, 70, 100), v));
	}

	@Test
	void sightIsASphere() {
		Vec3 eye = new Vec3(0, 80, 0);
		assertTrue(VanRules.inSight(new Vec3(0, 48, 0), eye));
		assertFalse(VanRules.inSight(new Vec3(0, 47.9, 0), eye));
	}

	@Test
	void forwardFollowsTheYaw() {
		Vec3 v = Vec3.ZERO;
		for (int i = 0; i < 40; i++) {
			v = VanRules.step(v, FORWARD, 0.0F);
		}

		// yaw 0 faces +z (south); the speed settles at the top speed
		assertEquals(0.0, v.x, 1e-6);
		assertEquals(VanRules.DRONE_SPEED, v.z, 1e-3);
		Vec3 west = Vec3.ZERO;
		for (int i = 0; i < 40; i++) {
			west = VanRules.step(west, FORWARD, 90.0F);
		}

		assertEquals(-VanRules.DRONE_SPEED, west.x, 1e-3);
		assertEquals(0.0, west.z, 1e-6);
	}

	@Test
	void strafeIsLeftOfForward() {
		VanRules.Keys left = new VanRules.Keys(false, false, true, false, false, false);
		Vec3 v = Vec3.ZERO;
		for (int i = 0; i < 40; i++) {
			v = VanRules.step(v, left, 0.0F);
		}

		// facing south (+z), left is east (+x)
		assertEquals(VanRules.DRONE_SPEED, v.x, 1e-3);
	}

	@Test
	void diagonalIsNoFaster() {
		VanRules.Keys both = new VanRules.Keys(true, false, true, false, false, false);
		Vec3 v = Vec3.ZERO;
		for (int i = 0; i < 60; i++) {
			v = VanRules.step(v, both, 33.0F);
		}

		assertEquals(VanRules.DRONE_SPEED, Math.sqrt(v.x * v.x + v.z * v.z), 1e-3);
	}

	@Test
	void climbAndSinkAreCapped() {
		VanRules.Keys up = new VanRules.Keys(false, false, false, false, true, false);
		VanRules.Keys down = new VanRules.Keys(false, false, false, false, false, true);
		assertEquals(VanRules.DRONE_CLIMB, VanRules.step(new Vec3(0, 5, 0), up, 0).y, 1e-9);
		Vec3 v = Vec3.ZERO;
		for (int i = 0; i < 40; i++) {
			v = VanRules.step(v, down, 0);
		}

		assertEquals(-VanRules.DRONE_CLIMB, v.y, 1e-3);
	}

	@Test
	void noKeysBrakesToAHover() {
		Vec3 v = new Vec3(0.5, 0.3, -0.4);
		for (int i = 0; i < 60; i++) {
			v = VanRules.step(v, VanRules.Keys.NONE, 120);
		}

		assertTrue(v.length() < 1e-6);
	}

	@Test
	void leashHoldsTheDroneAtTheEdge() {
		Vec3 van = new Vec3(10, 64, 10);
		Vec3 inside = van.add(30, 20, -40);
		assertEquals(inside, VanRules.leash(inside, van));
		Vec3 held = VanRules.leash(van.add(200, 0, 0), van);
		assertEquals(VanRules.DRONE_RANGE, held.distanceTo(van), 1e-9);
		assertEquals(van.x + VanRules.DRONE_RANGE, held.x, 1e-9);
	}

	@Test
	void batteryDrainsInFlightAndRefillsDocked() {
		assertEquals(99, VanRules.battery(100, true));
		assertEquals(0, VanRules.battery(0, true));
		assertEquals(100 + VanRules.RECHARGE, VanRules.battery(100, false));
		assertEquals(VanRules.BATTERY, VanRules.battery(VanRules.BATTERY - 1, false));
		// 90 s of flight, 30 s to refill from empty
		assertEquals(90 * 20, VanRules.BATTERY);
		assertEquals(30 * 20, VanRules.BATTERY / VanRules.RECHARGE);
		assertFalse(VanRules.mayLaunch(VanRules.LAUNCH_MIN - 1));
		assertTrue(VanRules.mayLaunch(VanRules.LAUNCH_MIN));
		assertEquals(0.5F, VanRules.share(VanRules.BATTERY / 2), 1e-6);
	}

	@Test
	void personWheelHasEveryChipButTheLeverAndTheAreaHacks() {
		List<VanRules.Option> o = VanRules.options(VanRules.Kind.PERSON, DeviceType.DOOR);
		for (Hack h : Hack.values()) {
			assertEquals(!h.needsDevice(), o.contains(new VanRules.Quickhack(h)), h.wire());
		}

		assertTrue(o.contains(new VanRules.Area(RemoteHack.OUTLINE)));
		assertTrue(o.contains(new VanRules.Area(RemoteHack.PING_AREA)));
		assertFalse(o.stream().anyMatch(x -> x instanceof VanRules.Vehicle || x instanceof VanRules.DeviceAct));
	}

	@Test
	void carWheelStartsWithTheVehiclePanel() {
		List<VanRules.Option> o = VanRules.options(VanRules.Kind.CAR, DeviceType.DOOR);
		assertEquals(new VanRules.Vehicle(RemoteHack.VEH_LOCK), o.getFirst());
		assertEquals(5, o.stream().filter(x -> x instanceof VanRules.Vehicle).count());
		assertTrue(o.contains(new VanRules.Quickhack(Hack.SHORT_CIRCUIT)));
		assertFalse(o.contains(new VanRules.Quickhack(Hack.DEVICE)));
	}

	@Test
	void deviceWheelFollowsTheType() {
		List<VanRules.Option> door = VanRules.options(VanRules.Kind.DEVICE, DeviceType.DOOR);
		assertEquals(List.of(new VanRules.DeviceAct("open"), new VanRules.DeviceAct("close"), new VanRules.DeviceAct("lock"), new VanRules.DeviceAct("unlock"),
			new VanRules.Quickhack(Hack.DEVICE)), door);
		assertEquals(List.of(new VanRules.Quickhack(Hack.DEVICE)), VanRules.options(VanRules.Kind.DEVICE, DeviceType.VENDING));
		assertTrue(VanRules.deviceActAllowed(DeviceType.TURRET, "friendly"));
		assertFalse(VanRules.deviceActAllowed(DeviceType.CAMERA, "friendly"));
		assertFalse(VanRules.deviceActAllowed(DeviceType.VENDING, "buy"));
	}

	@Test
	void everyDeviceActIsANightCityAct() {
		for (DeviceType t : DeviceType.values()) {
			for (String act : VanRules.deviceActs(t)) {
				assertTrue(dev.cyberblock.device.DeviceWire.NIGHT_CITY_ACTS.contains(act), t + " " + act);
			}
		}
	}
}
