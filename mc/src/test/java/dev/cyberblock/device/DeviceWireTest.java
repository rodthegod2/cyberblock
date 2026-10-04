package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.net.Permissions.DeviceType;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: the devices, devstate and devresult messages and the devact DOWN (protocol.md, Phase 4). */
class DeviceWireTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theProtocolExampleParses() {
		DeviceWire.Parsed p = DeviceWire.devices(json("{\"t\":\"devices\",\"list\":[{\"id\":\"11529215046068469763\",\"cls\":\"Door\",\"kind\":\"door\","
			+ "\"p\":[-1238.5,64.0,811.5],\"yaw\":90.0,\"st\":0,\"fl\":16},{\"id\":\"11529215046068469768\",\"cls\":\"Door\",\"kind\":\"door\","
			+ "\"p\":[-1244.5,64.0,811.5],\"yaw\":90.0,\"st\":-2,\"fl\":20}]}"), cls -> null);
		assertNotNull(p);
		assertEquals(2, p.devices().size());
		assertEquals(0, p.rejected());
		Device door = p.devices().getFirst();
		assertEquals("11529215046068469763", door.idText(), "ids above 2^63 stay unsigned");
		assertTrue(door.idString());
		assertEquals(DeviceType.DOOR, door.type());
		assertEquals(-1238.5, door.x());
		assertFalse(door.questLocked());
		assertTrue(p.devices().get(1).questLocked(), "fl bit 4: a sealed door");
	}

	@Test
	void badEntriesAreSkippedAndTheRestKept() {
		DeviceWire.Parsed p = DeviceWire.devices(json("{\"list\":["
			+ "{\"id\":\"1\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":0},"
			+ "{\"id\":\"1\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[1,64,0],\"yaw\":0,\"st\":0,\"fl\":0},"       // repeated id
			+ "{\"id\":\"2\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":2,\"fl\":0},"       // st out of range
			+ "{\"id\":\"3\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":32},"      // fl out of range
			+ "{\"id\":\"-4\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":0},"      // not a decimal id
			+ "{\"id\":\"5\",\"cls\":\"Spaceship\",\"kind\":\"rocket\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":0}," // no type
			+ "{\"id\":\"6\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,\"x\",0],\"yaw\":0,\"st\":0,\"fl\":0},"    // bad position
			+ "{\"id\":\"7\",\"cls\":\"" + "A".repeat(49) + "\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":0}" // class too long
			+ "]}"), cls -> null);
		assertEquals(1, p.devices().size());
		assertEquals(7, p.rejected());
	}

	@Test
	void theKindFallsBackToTheClassAndNumericIdsWork() {
		DeviceWire.Parsed p = DeviceWire.devices(json("{\"list\":[{\"id\":42,\"cls\":\"ElectricLight\",\"p\":[0,64,0],\"yaw\":0,\"st\":1,\"fl\":16}]}"),
			cls -> cls.equals("ElectricLight") ? DeviceType.LIGHT : null);
		Device d = p.devices().getFirst();
		assertEquals(DeviceType.LIGHT, d.type());
		assertEquals(42L, d.id());
		assertFalse(d.idString());
		assertEquals("{\"t\":\"devact\",\"id\":3,\"dev\":42,\"act\":\"off\"}", DeviceWire.devact(3, d, "off"), "the id goes back as it came");
	}

	@Test
	void theListIsCutAt64() {
		StringBuilder sb = new StringBuilder("{\"list\":[");
		for (int i = 1; i <= 70; i++) {
			sb.append(i > 1 ? "," : "").append("{\"id\":\"").append(i).append("\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[0,64,0],\"yaw\":0,\"st\":0,\"fl\":0}");
		}

		DeviceWire.Parsed p = DeviceWire.devices(json(sb.append("]}").toString()), cls -> null);
		assertEquals(DeviceWire.MAX_DEVICES, p.devices().size());
		assertEquals(6, p.rejected());
		assertNull(DeviceWire.devices(json("{\"t\":\"devices\"}"), cls -> null), "no list: nothing changes");
	}

	@Test
	void devstateAndDevresult() {
		DeviceWire.State s = DeviceWire.state(json("{\"t\":\"devstate\",\"id\":\"11529215046068469763\",\"st\":1,\"fl\":16,\"by\":\"guest\"}"));
		assertEquals(1, s.st());
		assertEquals("guest", s.by());
		assertNull(DeviceWire.state(json("{\"id\":\"1\",\"st\":1,\"fl\":16,\"by\":\"alien\"}")));
		assertEquals("script", DeviceWire.state(json("{\"id\":\"1\",\"st\":0,\"fl\":0}")).by());
		DeviceWire.Result r = DeviceWire.result(json("{\"t\":\"devresult\",\"id\":7,\"ok\":false,\"reason\":\"unsupported\"}"));
		assertEquals(7, r.id());
		assertFalse(r.ok());
		assertEquals("unsupported", r.reason());
		assertNull(DeviceWire.result(json("{\"id\":7,\"ok\":\"yes\"}")));
		assertNull(DeviceWire.result(json("{\"id\":7,\"ok\":false,\"reason\":\"" + "x".repeat(129) + "\"}")));
	}

	@Test
	void devactMatchesTheProtocolExample() {
		Device d = new Device(Long.parseUnsignedLong("11529215046068469763"), true, "Door", DeviceType.DOOR, -1238.5, 64, 811.5, 90, 0, 16);
		assertEquals("{\"t\":\"devact\",\"id\":7,\"dev\":\"11529215046068469763\",\"act\":\"open\"}", DeviceWire.devact(7, d, "open"));
	}
}
