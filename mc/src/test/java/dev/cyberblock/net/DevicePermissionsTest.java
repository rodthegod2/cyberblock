package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.net.Relay;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: the device type bits of section 1.11 on top of the devices toggle, and the Phase 4 UP types through the relay. */
class DevicePermissionsTest {
	private final UUID owner = new UUID(1, 1), otherHost = new UUID(2, 2), guest = new UUID(3, 3);

	@BeforeEach
	void fresh() {
		HostSessions.reset();
		Permissions.use(new HostsData());
		HostSessions.grant(this.owner, "CbHost1", null, json -> {});
	}

	@AfterEach
	void forget() {
		HostSessions.reset();
		Permissions.use(new HostsData());
	}

	@Test
	void theTypeBitsAreSection111s() {
		int[] bits = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
		String[] ids = {"door", "gate", "light", "camera", "terminal", "vending", "traffic", "elevator", "turret", "screen", "container"};
		for (int i = 0; i < bits.length; i++) {
			assertEquals(bits[i], DeviceType.values()[i].bit());
			assertEquals(ids[i], DeviceType.values()[i].id());
			assertSame(DeviceType.values()[i], DeviceType.byId(ids[i]));
		}

		assertNull(DeviceType.byId("garage"));
		int all = 0;
		for (DeviceType t : DeviceType.values()) {
			all |= t.bit();
		}

		assertEquals(Permissions.ALL_DEVICES, all);
	}

	@Test
	void aGuestNeedsTheTogglesAndTheTypeBit() {
		assertTrue(Permissions.allowsDevice(this.owner, this.guest, DeviceType.DOOR), "every type is on by default");
		Permissions.set(this.owner, Permissions.of(this.owner).with(DeviceType.DOOR, false));
		assertFalse(Permissions.allowsDevice(this.owner, this.guest, DeviceType.DOOR));
		assertTrue(Permissions.allowsDevice(this.owner, this.guest, DeviceType.LIGHT), "one type off leaves the others on");
		assertTrue(Permissions.allowsDevice(this.owner, this.owner, DeviceType.DOOR), "the host on its own game");
		HostSessions.grant(this.otherHost, "CbHost2", null, json -> {});
		assertTrue(Permissions.allowsDevice(this.owner, this.otherHost, DeviceType.DOOR), "another host is no guest");
		assertTrue(Permissions.allowsDevice(this.owner, null, DeviceType.DOOR), "the world is no guest");
		Permissions.set(this.owner, Permissions.of(this.owner).with(DeviceType.DOOR, true).with(Permissions.Toggle.DEVICES, false));
		for (DeviceType t : DeviceType.values()) {
			assertFalse(Permissions.allowsDevice(this.owner, this.guest, t), "devices off gates every type: " + t.id());
		}

		assertEquals(Permissions.ALL_DEVICES, Permissions.of(this.owner).devices(), "the type bits are kept while the toggle is off");
	}

	@Test
	void thePhase4UpTypesPassTheRelayAndTheGuard() {
		for (String t : new String[] {"devices", "devstate", "devresult"}) {
			assertEquals(Relay.Route.UP, Relay.route(t), t);
			assertTrue(RelayGuard.isUpType(t), t);
		}

		RelayGuard g = new RelayGuard(1.0, 160.0, () -> 0L);
		assertTrue(g.check(true, "devresult", "{\"t\":\"devresult\",\"id\":5,\"ok\":true,\"reason\":\"\"}", 0, 0, id -> id == 5).messages().size() == 1);
		assertTrue(g.check(true, "devresult", "{\"t\":\"devresult\",\"id\":6,\"ok\":true,\"reason\":\"\"}", 0, 0, id -> id == 5).messages().isEmpty(),
			"a reply needs a request id the session issued");
		var far = g.check(true, "devices", "{\"t\":\"devices\",\"list\":[{\"id\":\"1\",\"p\":[0,64,0]},{\"id\":\"2\",\"p\":[500,64,0]}]}", 0, 0, id -> false);
		assertEquals(1, far.messages().getFirst().m().getAsJsonArray("list").size(), "a device beyond the sanity radius is dropped");
		assertTrue((ServerNet.FEATURES & Payloads.FEATURE_DEVICES) != 0, "hs_ack.features bit 2");
	}
}
