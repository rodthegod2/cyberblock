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
			assertTrue(h.langKey().matches("cyberblock\\.hack\\.(vehicle|traffic|netmap)\\.[a-z_]+"), h.langKey());
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
		// the gridlock stops a road for a minute; only the area hacks have a radius (whole metres, 1..150 on the wire)
		assertEquals(60, RemoteHack.GRIDLOCK.seconds());
		assertEquals(24, RemoteHack.GRIDLOCK.radius());
		assertEquals(150, RemoteHack.BLACKOUT.radius());
		assertEquals(0, RemoteHack.VEH_JAM.radius());
		for (RemoteHack h : RemoteHack.values()) {
			assertEquals(h.target() == RemoteHack.Target.DISTRICT || h.target() == RemoteHack.Target.POINT, h.area(), h.wire());
			assertTrue(h.area() ? h.radius() >= 1 && h.radius() <= 150 : h.radius() == 0, h.wire());
		}

		// shared/protocol.md: vehicle hacks 30 s, traffic_switch and gridlock 60 s, a blackout the player's length, a ping a one-shot
		assertEquals(30, RemoteHack.VEH_BRAKE.downSeconds(0));
		assertEquals(60, RemoteHack.TRAFFIC_SWITCH.downSeconds(300));
		assertEquals(240, RemoteHack.BLACKOUT.downSeconds(240));
		assertEquals(0, RemoteHack.PING_AREA.downSeconds(0));
	}

	@Test
	void guestsNeedTheOwnersToggles() {
		Permissions none = new Permissions(0, 0);
		Permissions hacksOnly = new Permissions(Payloads.PERM_HACKS, 0);
		Permissions hacksNoTraffic = new Permissions(Payloads.PERM_HACKS | Payloads.PERM_DEVICES,
			Permissions.ALL_DEVICES & ~Permissions.DeviceType.TRAFFIC.bit());
		// the owner itself (or another host) is never gated
		for (RemoteHack h : RemoteHack.values()) {
			assertTrue(h.allowed(none, true), h.wire());
			assertFalse(h.allowed(none, false), h.wire());
		}

		assertTrue(RemoteHack.VEH_LOCK.allowed(hacksOnly, false));
		assertTrue(RemoteHack.OUTLINE.allowed(hacksOnly, false));
		assertTrue(RemoteHack.PING_AREA.allowed(hacksOnly, false));
		assertFalse(RemoteHack.BLACKOUT.allowed(hacksOnly, false), "a blackout is a device action");
		assertFalse(RemoteHack.TRAFFIC_SWITCH.allowed(hacksNoTraffic, false), "the traffic bit is off");
		assertFalse(RemoteHack.GRIDLOCK.allowed(hacksNoTraffic, false));
		assertTrue(RemoteHack.BLACKOUT.allowed(hacksNoTraffic, false), "lights stay allowed");
		assertTrue(RemoteHack.CAM_FEED.allowed(hacksNoTraffic, false));
		assertTrue(RemoteHack.CAM_FEED.allowed(Permissions.DEFAULT, false));
	}

	@Test
	void hacksOffBlocksDeviceHacksEvenWithTheirDeviceBitOn() {
		// section 1.6: `hacks` gates guest quickhacks on the host's NPCs, cars AND devices (`guests hacks off` stops the Net Map)
		Permissions devicesOnly = new Permissions(Payloads.PERM_DEVICES, Permissions.ALL_DEVICES);
		for (RemoteHack h : List.of(RemoteHack.BLACKOUT, RemoteHack.TRAFFIC_SWITCH, RemoteHack.GRIDLOCK, RemoteHack.CAM_FEED)) {
			assertFalse(h.allowed(devicesOnly, false), h.wire());
			assertTrue(h.allowed(devicesOnly, true), h.wire() + ": the owner and other hosts are never gated");
		}
	}
}
