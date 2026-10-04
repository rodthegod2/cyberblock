package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionsTest {
	private final UUID owner = new UUID(1, 1), otherHost = new UUID(2, 2), guest = new UUID(3, 3);
	private HostsData data;

	@BeforeEach
	void fresh() {
		HostSessions.reset();
		this.data = new HostsData();
		Permissions.use(this.data);
		HostSessions.grant(this.owner, "CbHost1", null, json -> {});
	}

	@AfterEach
	void forget() {
		HostSessions.reset();
		Permissions.use(new HostsData());
	}

	@Test
	void defaultsAreEverythingButSpawns() {
		Permissions p = Permissions.of(this.owner);
		assertEquals(Permissions.DEFAULT, p);
		assertTrue(p.has(Permissions.Toggle.EXPLOSIONS));
		assertTrue(p.has(Permissions.Toggle.DAMAGE));
		assertTrue(p.has(Permissions.Toggle.HACKS));
		assertTrue(p.has(Permissions.Toggle.DEVICES));
		assertFalse(p.has(Permissions.Toggle.SPAWNS));
		assertEquals(15, p.bits());
		assertTrue(p.device(1));
		assertTrue(p.device(1024));
	}

	@Test
	void aToggleGatesOnlyGuests() {
		Permissions.set(this.owner, Permissions.of(this.owner).with(Permissions.Toggle.EXPLOSIONS, false));
		assertFalse(Permissions.allows(this.owner, this.guest, Permissions.Toggle.EXPLOSIONS));
		assertTrue(Permissions.allows(this.owner, this.guest, Permissions.Toggle.DAMAGE));
		assertTrue(Permissions.allows(this.owner, this.owner, Permissions.Toggle.EXPLOSIONS), "the host's own actions are always allowed");
		assertTrue(Permissions.allows(this.owner, null, Permissions.Toggle.EXPLOSIONS), "a creeper is not a guest");
		HostSessions.grant(this.otherHost, "CbHost2", null, json -> {});
		assertTrue(Permissions.allows(this.owner, this.otherHost, Permissions.Toggle.EXPLOSIONS), "another host is not a guest");
		assertFalse(Permissions.allows(this.owner, this.guest, Permissions.Toggle.SPAWNS), "spawns are off by default");
	}

	@Test
	void togglesAreStoredPerHostAndMarkTheWorldDirty() {
		assertFalse(this.data.isDirty());
		Permissions.set(this.owner, new Permissions(Permissions.ALL, 5));
		assertTrue(this.data.isDirty());
		assertEquals(new Permissions(31, 5), Permissions.of(this.owner));
		assertEquals(Permissions.DEFAULT, Permissions.of(this.guest));
		assertFalse(Permissions.of(this.owner).device(2));
		assertTrue(Permissions.of(this.owner).device(4));
	}

	@Test
	void outOfRangeBitsAreMasked() {
		Permissions p = new Permissions(-1, -1);
		assertEquals(31, p.bits());
		assertEquals(Permissions.ALL_DEVICES, p.devices());
	}

	@Test
	void devicesOffGatesEveryDeviceType() {
		Permissions p = Permissions.DEFAULT.with(Permissions.Toggle.DEVICES, false);
		assertFalse(p.device(1));
	}

	@Test
	void toggleIdsAreTheCommandNames() {
		assertEquals("explosions", Permissions.Toggle.EXPLOSIONS.id());
		assertSame(Permissions.Toggle.SPAWNS, Permissions.Toggle.byId("spawns"));
		assertNull(Permissions.Toggle.byId("nukes"));
	}

	@Test
	void hostsDataRoundTripsThroughItsCodec() {
		Permissions.set(this.owner, new Permissions(3, 9));
		this.data.setLastSession(this.owner, 1234L);
		var encoded = HostsData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, this.data).getOrThrow();
		HostsData back = HostsData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
		assertEquals(new Permissions(3, 9), back.perms(this.owner));
		assertEquals(1234L, back.lastSession(this.owner));
	}
}
