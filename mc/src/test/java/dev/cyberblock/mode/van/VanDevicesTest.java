package dev.cyberblock.mode.van;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.device.Device;
import dev.cyberblock.device.DeviceRules;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VanDevicesTest {
	private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

	private static Device door(final long id) {
		return new Device(id, false, "Door", DeviceType.DOOR, 1, 64, 2, 0.0F, Device.DOOR_CLOSED, Device.POWERED);
	}

	@Test
	void listsPerOwnerAndForgetsTheGone() {
		VanDevices d = new VanDevices();
		d.update(A, List.of(door(1), door(2)), 0);
		d.update(B, List.of(door(1)), 0);
		assertEquals(2, d.of(A, 10).size());
		assertEquals(1, d.of(B, 10).size());
		d.update(A, List.of(door(2)), 150);
		assertEquals(List.of(door(2)), d.of(A, DeviceRules.GONE_TICKS));
		assertNull(d.get(A, 1, DeviceRules.GONE_TICKS));
		assertNotNull(d.get(A, 2, DeviceRules.GONE_TICKS));
	}

	@Test
	void stateChangesKeepTheLastSighting() {
		VanDevices d = new VanDevices();
		d.update(A, List.of(door(1)), 0);
		d.state(A, 1, Device.DOOR_OPENED, Device.POWERED);
		d.state(A, 9, Device.DOOR_OPENED, 0);
		d.state(B, 1, Device.DOOR_OPENED, 0);
		Device now = d.get(A, 1, 10);
		assertNotNull(now);
		assertTrue(now.open());
		assertNull(d.get(A, 1, DeviceRules.GONE_TICKS));
		assertTrue(d.of(B, 0).isEmpty());
	}

	@Test
	void endForgetsTheOwner() {
		VanDevices d = new VanDevices();
		d.update(A, List.of(door(1)), 0);
		d.update(B, List.of(door(1)), 0);
		d.end(A);
		assertEquals(List.of(B), d.owners());
		d.clear();
		assertTrue(d.owners().isEmpty());
	}
}
