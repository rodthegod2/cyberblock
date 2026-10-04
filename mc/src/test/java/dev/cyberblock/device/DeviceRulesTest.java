package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.net.Permissions.DeviceType;
import dev.cyberblock.replica.ReplicaBlocks;
import dev.cyberblock.replica.ReplicaTestBlocks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: placement rules, what a use asks for, refused acts, the state after an ok, the removal timer. */
class DeviceRulesTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Device of(final DeviceType type, final int st, final int fl) {
		return new Device(9, true, "X", type, 0, 64, 0, 0, st, fl);
	}

	@Test
	void deviceBlocksGoOnlyIntoAirOrReplicaCells() {
		BlockState door = Blocks.OAK_DOOR.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
		BlockState replica = ReplicaTestBlocks.state(ReplicaBlocks.CONCRETE_LIGHT);
		assertTrue(DeviceRules.mayPlace(air, door, null, null));
		assertTrue(DeviceRules.mayPlace(replica, door, null, null), "a replica block gives way to the device");
		assertFalse(DeviceRules.mayPlace(stone, door, null, null), "never over a player's block");
		assertFalse(DeviceRules.mayPlace(air, door, false, null), "never into another device's cell");
		assertTrue(DeviceRules.mayPlace(door, door.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true), true, door), "its own block changes state");
		assertFalse(DeviceRules.mayPlace(stone, door, true, door), "a player's block took the cell since: it stays");
		assertFalse(DeviceRules.mayPlace(door, door, null, null), "a vanilla block equal to the wanted one may be a player's: never claimed");
		BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
		assertFalse(DeviceRules.mayPlace(iron, iron, null, null), "nor a block equal to a vanilla stand-in");
		assertFalse(DeviceRules.unownable(iron));
	}

	@Test
	void aRightClickAsksForTheOppositeOfWhatTheBlockShows() {
		assertEquals("open", DeviceRules.use(of(DeviceType.DOOR, Device.DOOR_CLOSED, 0), false).act());
		assertEquals("close", DeviceRules.use(of(DeviceType.DOOR, Device.DOOR_OPENED, 0), false).act());
		assertEquals("off", DeviceRules.use(of(DeviceType.LIGHT, Device.ST_ON, 16), false).act());
		assertEquals("on", DeviceRules.use(of(DeviceType.CAMERA, Device.ST_OFF, 16), false).act());
		assertEquals("friendly", DeviceRules.use(of(DeviceType.TURRET, Device.ST_ON, 16), true).act(), "sneak: make a turret friendly");
		assertEquals("open", DeviceRules.use(of(DeviceType.GATE, Device.ST_OFF, 16), false).act());
		assertEquals("distract", DeviceRules.use(of(DeviceType.SCREEN, Device.ST_ON, 16), false).act());
		assertEquals(DeviceRules.Outcome.SHOP, DeviceRules.use(of(DeviceType.VENDING, Device.ST_ON, 16), false).outcome(), "vending is a Minecraft shop");
	}

	@Test
	void greyedAndLockedDevicesSendNothing() {
		DeviceRules.Use quest = DeviceRules.use(of(DeviceType.DOOR, Device.DOOR_SEALED, Device.QUEST_LOCKED), false);
		assertEquals(DeviceRules.Outcome.REFUSED, quest.outcome());
		assertEquals("cyberblock.device.quest_locked", quest.key());
		assertEquals("cyberblock.device.locked", DeviceRules.use(of(DeviceType.DOOR, Device.DOOR_LOCKED, Device.LOCKED), false).key());
		assertEquals("cyberblock.device.unsupported", DeviceRules.use(of(DeviceType.ELEVATOR, Device.ST_ON, 16), false).key(), "a lift call is unproven");
		assertEquals(DeviceRules.Outcome.REFUSED, DeviceRules.use(of(DeviceType.TERMINAL, Device.ST_ON, 16), false).outcome(), "Breach is Phase 6");
		assertEquals("cyberblock.device.broken", DeviceRules.use(of(DeviceType.VENDING, Device.ST_ON, Device.BROKEN), false).key());
		assertEquals(DeviceRules.Outcome.REFUSED, DeviceRules.use(of(DeviceType.CONTAINER, Device.ST_ON, 0), false).outcome());
	}

	@Test
	void explicitActsFollowTheContract() {
		Device lamp = of(DeviceType.LIGHT, Device.ST_ON, 16);
		assertEquals(DeviceRules.Use.send("toggle"), DeviceRules.request(lamp, "toggle"));
		assertEquals("cyberblock.device.unsupported", DeviceRules.request(lamp, "call").key(), "call is greyed (Phase 0 c)");
		assertEquals("cyberblock.device.unsupported", DeviceRules.request(lamp, "breach").key());
		assertEquals("cyberblock.device.unsupported", DeviceRules.request(lamp, "buy").key(), "only a vending machine sells");
		assertEquals(DeviceRules.Outcome.SHOP, DeviceRules.request(of(DeviceType.VENDING, 1, 16), "buy").outcome());
		Device locked = of(DeviceType.DOOR, Device.DOOR_LOCKED, Device.LOCKED);
		assertEquals("cyberblock.device.locked", DeviceRules.request(locked, "unlock").key(), "unlocking needs Breach (Phase 6)");
		assertEquals("cyberblock.device.locked", DeviceRules.request(locked, "toggle").key());
		assertEquals(DeviceRules.Use.send("lock"), DeviceRules.request(of(DeviceType.DOOR, Device.DOOR_CLOSED, 0), "lock"));
		assertEquals(DeviceRules.Outcome.REFUSED, DeviceRules.request(of(DeviceType.LIGHT, Device.ST_DISABLED, Device.QUEST_LOCKED), "on").outcome());
	}

	@Test
	void okShowsTheActsStateAndActsThatChangeNothingShowNothing() {
		Device door = of(DeviceType.DOOR, Device.DOOR_CLOSED, 16);
		assertEquals(Device.DOOR_OPENED, DeviceRules.expected(door, "open").st());
		assertEquals(Device.DOOR_OPENED, DeviceRules.expected(door, "toggle").st());
		Device locked = DeviceRules.expected(door, "lock");
		assertEquals(Device.DOOR_LOCKED, locked.st());
		assertTrue(locked.locked());
		Device unlocked = DeviceRules.expected(locked, "unlock");
		assertEquals(Device.DOOR_CLOSED, unlocked.st());
		assertFalse(unlocked.locked());
		assertEquals(Device.ST_OFF, DeviceRules.expected(of(DeviceType.LIGHT, Device.ST_ON, 16), "off").st());
		assertNull(DeviceRules.expected(of(DeviceType.SCREEN, Device.ST_ON, 16), "distract"));
		assertNull(DeviceRules.expected(of(DeviceType.TURRET, Device.ST_ON, 16), "friendly"));
	}

	@Test
	void aDeviceGoesTenSecondsAfterItsLastList() {
		assertFalse(DeviceRules.gone(100, 100 + DeviceRules.GONE_TICKS - 1));
		assertTrue(DeviceRules.gone(100, 100 + DeviceRules.GONE_TICKS));
	}
}
