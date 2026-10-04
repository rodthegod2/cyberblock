package dev.cyberblock.hack.redstone;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.device.Device;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 6-R1: a link is (owner, rounded Cyberpunk position, class) and resolves against the owner's table at runtime. */
class LinkTest {
	private static final UUID OWNER = new UUID(1, 1);

	static Device device(final long id, final String cls, final DeviceType type, final double x, final double y, final double z) {
		return new Device(id, true, cls, type, x, y, z, 0.0F, Device.DOOR_CLOSED, Device.POWERED);
	}

	@Test
	void theLinkStoresTheRoundedCyberpunkPositionAndClass() {
		Link link = Link.of(OWNER, device(7, "Door", DeviceType.DOOR, 10.12, 64.0, -20.4), 32);
		assertEquals(OWNER, link.owner());
		assertEquals("Door", link.cls());
		assertEquals(10.0, link.x(), 1e-9, "x stays x, rounded to 25 cm");
		assertEquals(20.5, link.y(), 1e-9, "Cyberpunk y is minus Minecraft z");
		assertEquals(64.5, link.z(), 1e-9, "Cyberpunk z is Minecraft y + k/64");
	}

	@Test
	void aNewSessionsIdsDoNotBreakTheLink() {
		Link link = Link.of(OWNER, device(7, "Door", DeviceType.DOOR, 10.1, 64.0, -20.4), 0);
		// after a restart Night City sends another id for the same door, a little off the old rounding
		Device again = device(99, "Door", DeviceType.DOOR, 10.05, 64.02, -20.45);
		Device other = device(8, "Door", DeviceType.DOOR, 14.0, 64.0, -20.4);
		Device light = device(9, "ElectricLight", DeviceType.LIGHT, 10.1, 64.0, -20.4);
		assertSame(again, link.resolve(List.of(other, light, again), 0));
	}

	@Test
	void unresolvedWhenTheDeviceIsNotInTheTable() {
		Link link = Link.of(OWNER, device(7, "Door", DeviceType.DOOR, 10.1, 64.0, -20.4), 0);
		assertNull(link.resolve(List.of(), 0), "owner offline: an empty table");
		assertNull(link.resolve(List.of(device(7, "Door", DeviceType.DOOR, 12.0, 64.0, -20.4)), 0), "moved two metres: another device");
		assertNull(link.resolve(List.of(device(7, "Terminal", DeviceType.TERMINAL, 10.1, 64.0, -20.4)), 0), "another class at the place");
	}

	@Test
	void theNearestOfTwoMatchesWins() {
		Link link = Link.of(OWNER, device(1, "Door", DeviceType.DOOR, 0.0, 64.0, 0.0), 0);
		Device near = device(2, "Door", DeviceType.DOOR, 0.05, 64.0, 0.0);
		Device far = device(3, "Door", DeviceType.DOOR, 0.25, 64.0, 0.0);
		assertSame(near, link.resolve(List.of(far, near), 0));
	}

	@Test
	void aRealignedWorldKeepsTheLink() {
		// the world's ground alignment k moved by 32/64: Minecraft y of the same device moved by half a block the other way
		Link link = Link.of(OWNER, device(1, "Door", DeviceType.DOOR, 5.0, 64.0, 5.0), 0);
		assertNotNull(link.resolve(List.of(device(1, "Door", DeviceType.DOOR, 5.0, 63.5, 5.0)), 32));
	}

	@Test
	void linksToTheSameDeviceShareOneKeyAndNeverNegativeZero() {
		Link a = Link.of(OWNER, device(1, "Door", DeviceType.DOOR, 0.0, 64.0, 0.0), 0);
		Link b = Link.of(OWNER, device(2, "Door", DeviceType.DOOR, 0.01, 64.0, -0.01), 0);
		assertEquals(a, b);
		assertEquals(a.key(), b.key());
		assertNotEquals(a.key(), Link.of(new UUID(2, 2), device(1, "Door", DeviceType.DOOR, 0.0, 64.0, 0.0), 0).key(), "the owner is part of it");
	}

	@Test
	void badLinksAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> new Link(OWNER, 0, 0, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new Link(OWNER, Double.NaN, 0, 0, "Door"));
		assertThrows(IllegalArgumentException.class, () -> new Link(OWNER, 0, 0, 0, "x".repeat(49)));
	}
}
