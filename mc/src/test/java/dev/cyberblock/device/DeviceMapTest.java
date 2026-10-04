package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: the device_map mapping (4A's schema) and the built-in defaults. */
class DeviceMapTest {
	private static final Identifier DOOR_ID = Identifier.fromNamespaceAndPath("cyberblock", "door");

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static Device device(final String cls, final DeviceType type, final int fl) {
		return new Device(1, true, cls, type, 0.5, 64, 0.5, 0, 0, fl);
	}

	@AfterEach
	void builtInOnly() {
		DeviceMap.use(Map.of());
	}

	@Test
	void fourAsDoorFileParses() {
		DeviceMap.Entry e = DeviceMap.parse(DOOR_ID, json("{\"schema\": 1, \"class\": \"Door\", \"kind\": \"door\", \"block\": \"minecraft:oak_door\", "
			+ "\"alt_block\": \"minecraft:iron_door\", \"alt_when\": \"secure\", \"size\": [1, 2, 1], \"attachments\": [{\"block\": \"cyberblock:lamp_red\", "
			+ "\"offset\": [0, 2, 0], \"when\": \"locked\"}]}"));
		assertEquals(DeviceType.DOOR, e.type());
		assertEquals("minecraft:oak_door", e.blockFor(device("Door", DeviceType.DOOR, 0)));
		assertEquals("minecraft:iron_door", e.blockFor(device("Door", DeviceType.DOOR, Device.SECURE)), "a secure door is an iron door");
		assertEquals(List.of(new DeviceMap.Attachment("cyberblock:lamp_red", 0, 2, 0, true)), e.attachments());
		assertEquals(2, e.h());
	}

	@Test
	void fourAsVendingFileListsItsShops() {
		DeviceMap.Entry e = DeviceMap.parse(DOOR_ID, json("{\"schema\": 1, \"class\": \"VendingMachine\", \"kind\": \"vending\", \"block\": "
			+ "\"cyberblock:vending_machine\", \"size\": [1, 1, 1], \"shops\": [\"cyberblock:vending_snacks\", \"cyberblock:vending_drinks\"]}"));
		assertEquals(List.of("cyberblock:vending_snacks", "cyberblock:vending_drinks"), e.shops());
	}

	@Test
	void badFilesAreRefusedWithTheirReason() {
		String[] bad = {
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"portal\",\"block\":\"minecraft:oak_door\",\"size\":[1,2,1]}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[0,2,1]}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[1,17,1]}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[1,2,1],\"alt_block\":\"minecraft:iron_door\"}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"oak door\",\"size\":[1,2,1]}",
			"{\"schema\":1,\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[1,2,1]}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[1,2,1],\"attachments\":[{\"block\":\"minecraft:stone\",\"offset\":[0,99,0]}]}",
			"{\"schema\":1,\"class\":\"Door\",\"kind\":\"door\",\"block\":\"minecraft:oak_door\",\"size\":[1,2,1],\"shops\":[]}"};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> DeviceMap.parse(DOOR_ID, json(s)), s);
		}
	}

	@Test
	void classesMapToTheirTypeWithOrWithoutContent() {
		assertEquals(DeviceType.DOOR, DeviceMap.typeOf("Door"));
		assertEquals(DeviceType.GATE, DeviceMap.typeOf("RoadBlock"), "road barriers are gates");
		assertEquals(DeviceType.SCREEN, DeviceMap.typeOf("TV"));
		assertEquals(DeviceType.VENDING, DeviceMap.typeOf("WeaponVendingMachine"));
		assertNull(DeviceMap.typeOf("GarageDoor"), "there is no GarageDoor class (Phase 0 c)");
		DeviceMap.use(Map.of(DOOR_ID, DeviceMap.parse(DOOR_ID, json("{\"schema\":1,\"class\":\"GarageDoor\",\"kind\":\"gate\","
			+ "\"block\":\"cyberblock:sliding_gate\",\"size\":[4,3,1]}"))));
		assertEquals(DeviceType.GATE, DeviceMap.typeOf("GarageDoor"), "a datapack can map a new class");
	}

	@Test
	void aClassWithoutAFileTakesItsKindsDefault() {
		DeviceMap.Entry e = DeviceMap.entry("IceMachine", DeviceType.VENDING);
		assertEquals(DeviceBlocks.VENDING, e.block());
		assertFalse(e.shops().isEmpty(), "the built-in vending default sells from 4A's shops");
		DeviceMap.Entry door = DeviceMap.entry("Door", DeviceType.DOOR);
		assertEquals("minecraft:oak_door", door.block());
		assertTrue(door.attachments().getFirst().lockedOnly(), "the built-in door has the lock lamp too");
		// a class mapped to another type than the message says: the type's default
		DeviceMap.use(Map.of(DOOR_ID, DeviceMap.parse(DOOR_ID, json("{\"schema\":1,\"class\":\"Door\",\"kind\":\"gate\","
			+ "\"block\":\"cyberblock:sliding_gate\",\"size\":[4,3,1]}"))));
		assertEquals("minecraft:oak_door", DeviceMap.entry("Door", DeviceType.DOOR).block());
		assertEquals("cyberblock:sliding_gate", DeviceMap.entry("door", DeviceType.GATE).block(), "classes match whatever their case");
	}
}
