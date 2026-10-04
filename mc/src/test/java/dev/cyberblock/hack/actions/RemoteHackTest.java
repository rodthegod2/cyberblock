package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.net.Payloads;
import dev.cyberblock.net.Permissions;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan section 1.9 row 6 (`rhack.hack` values) and section 1.6 (who may send which). */
class RemoteHackTest {
	/** Section 1.9, copied by hand: the values `hack` adds for rhack. */
	private static final List<String> PLAN = List.of("veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam", "traffic_switch", "gridlock",
		"blackout", "cam_feed", "outline", "ping_area");

	@Test
	void wireNamesAreThePlans() {
		assertEquals(PLAN, Arrays.stream(RemoteHack.values()).map(RemoteHack::wire).toList());
		assertSame(RemoteHack.CAM_FEED, RemoteHack.byWire("cam_feed"));
		assertNull(RemoteHack.byWire("CAM_FEED"));
		assertNull(RemoteHack.byWire(null));
		for (RemoteHack h : RemoteHack.values()) {
			assertEquals("cyberblock.rhack." + h.wire(), h.langKey());
			assertFalse(h.english().isEmpty());
			assertTrue(h.cooldownSeconds() > 0, h.wire());
		}
	}

	@Test
	void targets() {
		for (RemoteHack h : List.of(RemoteHack.VEH_LOCK, RemoteHack.VEH_ENGINE_OFF, RemoteHack.VEH_BRAKE, RemoteHack.VEH_START, RemoteHack.VEH_JAM)) {
			assertSame(RemoteHack.Target.VEHICLE, h.target());
		}

		assertSame(RemoteHack.Target.DISTRICT, RemoteHack.TRAFFIC_SWITCH.target());
		assertSame(RemoteHack.Target.DISTRICT, RemoteHack.BLACKOUT.target());
		assertSame(RemoteHack.Target.CAMERA, RemoteHack.CAM_FEED.target());
		assertSame(RemoteHack.Target.POINT, RemoteHack.GRIDLOCK.target());
		// the gridlock stops a road for a minute; only the point hacks have a radius
		assertEquals(60, RemoteHack.GRIDLOCK.seconds());
		assertEquals(24.0, RemoteHack.GRIDLOCK.radius());
		assertEquals(0.0, RemoteHack.VEH_JAM.radius());
	}

	@Test
	void guestsNeedTheOwnersToggles() {
		Permissions none = new Permissions(0, 0);
		Permissions hacksOnly = new Permissions(Payloads.PERM_HACKS, 0);
		Permissions devicesNoTraffic = new Permissions(Payloads.PERM_DEVICES, Permissions.ALL_DEVICES & ~Permissions.DeviceType.TRAFFIC.bit());
		// the owner itself (or another host) is never gated
		for (RemoteHack h : RemoteHack.values()) {
			assertTrue(h.allowed(none, true), h.wire());
			assertFalse(h.allowed(none, false), h.wire());
		}

		assertTrue(RemoteHack.VEH_LOCK.allowed(hacksOnly, false));
		assertTrue(RemoteHack.OUTLINE.allowed(hacksOnly, false));
		assertTrue(RemoteHack.PING_AREA.allowed(hacksOnly, false));
		assertFalse(RemoteHack.BLACKOUT.allowed(hacksOnly, false), "a blackout is a device action");
		assertFalse(RemoteHack.TRAFFIC_SWITCH.allowed(devicesNoTraffic, false), "the traffic bit is off");
		assertFalse(RemoteHack.GRIDLOCK.allowed(devicesNoTraffic, false));
		assertTrue(RemoteHack.BLACKOUT.allowed(devicesNoTraffic, false), "lights stay allowed");
		assertTrue(RemoteHack.CAM_FEED.allowed(devicesNoTraffic, false));
		assertTrue(RemoteHack.CAM_FEED.allowed(Permissions.DEFAULT, false));
	}
}
