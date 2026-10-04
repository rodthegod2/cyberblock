package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.net.Permissions.DeviceType;
import dev.cyberblock.replica.ReplicaBlocks;
import dev.cyberblock.replica.ReplicaTestBlocks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: the registry per owner on a fake world: placement, the replica filter, state, refusals, removal timers, session end. */
class DeviceRegistryTest {
	private static final UUID HOST = new UUID(1, 1), OTHER = new UUID(2, 2);
	private final Map<Long, BlockState> world = new HashMap<>();
	private final DeviceRegistry.Cells cells = new DeviceRegistry.Cells() {
		@Override
		public BlockState get(final long cell) {
			return DeviceRegistryTest.this.world.getOrDefault(cell, Blocks.AIR.defaultBlockState());
		}

		@Override
		public void set(final long cell, final BlockState state) {
			DeviceRegistryTest.this.world.put(cell, state);
		}
	};
	private DeviceRegistry registry;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void fresh() {
		DeviceMap.use(Map.of());
		// the vanilla stand-ins: the test game has no cyberblock blocks
		this.registry = new DeviceRegistry(d -> {
			DeviceMap.Entry e = DeviceMap.entry(d.cls(), d.type());
			return new DeviceRegistry.Look(e, new DeviceShape.Blocks(DeviceBlocks.resolve(e.blockFor(d), d.type()), List.of(Blocks.REDSTONE_LAMP.defaultBlockState())));
		});
	}

	private static Device door(final long id, final int x, final int st, final int fl) {
		return new Device(id, true, "Door", DeviceType.DOOR, x + 0.5, 64.0, 0.5, 0, st, fl);
	}

	private static long cell(final int x, final int y, final int z) {
		return new BlockPos(x, y, z).asLong();
	}

	@Test
	void aDoorIsPlacedAndKeepsTheReplicaOut() {
		this.world.put(cell(0, 64, 0), ReplicaTestBlocks.state(ReplicaBlocks.CONCRETE_LIGHT)); // the probes had filled the doorway
		DeviceRegistry.Change c = this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_CLOSED, 16)), 0, this.cells);
		assertEquals(1, c.added());
		assertEquals(Blocks.OAK_DOOR, this.world.get(cell(0, 64, 0)).getBlock(), "the replica block gave way");
		assertEquals(Blocks.OAK_DOOR, this.world.get(cell(0, 65, 0)).getBlock());
		assertTrue(this.registry.excluded(cell(0, 64, 0)));
		assertTrue(this.registry.excluded(cell(0, 65, 0)));
		assertFalse(this.registry.excluded(cell(5, 64, 0)));
		assertSame(this.registry.get(HOST, 1), this.registry.at(cell(0, 65, 0)), "either half finds the device");
	}

	@Test
	void neverOverAPlayersBlockAndNeverHalfADoor() {
		this.world.put(cell(0, 65, 0), Blocks.STONE.defaultBlockState()); // a player's block where the upper half would go
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_CLOSED, 16)), 0, this.cells);
		assertEquals(Blocks.STONE, this.world.get(cell(0, 65, 0)).getBlock());
		assertNull(this.world.get(cell(0, 64, 0)), "no lone lower half");
		assertNull(this.registry.at(cell(0, 64, 0)));
		assertTrue(this.registry.excluded(cell(0, 64, 0)), "the box still keeps the replica out");
	}

	@Test
	void twoHostsReportingOneDoorNeverFight() {
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_CLOSED, 16)), 0, this.cells);
		this.registry.update(OTHER, List.of(door(1, 0, Device.DOOR_OPENED, 16)), 0, this.cells);
		assertFalse(this.world.get(cell(0, 64, 0)).getValue(DoorBlock.OPEN), "the first host's door holds the cells");
		assertEquals(HOST, this.registry.at(cell(0, 64, 0)).key().owner());
		this.registry.end(HOST, this.cells);
		assertTrue(this.world.get(cell(0, 64, 0)).isAir(), "the first host's door went");
		this.registry.update(OTHER, List.of(door(1, 0, Device.DOOR_OPENED, 16)), 20, this.cells);
		assertTrue(this.world.get(cell(0, 64, 0)).getValue(DoorBlock.OPEN), "the other host's door takes the cells on its next list");
	}

	@Test
	void devstateShowsAndARefusalKeepsTheOldState() {
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_CLOSED, 16)), 0, this.cells);
		// a refused devact: nothing calls state(), so the door stays shut
		assertFalse(this.world.get(cell(0, 64, 0)).getValue(DoorBlock.OPEN));
		// an NPC opens it (devstate by npc)
		assertNotNull(this.registry.state(HOST, 1, Device.DOOR_OPENED, 16, this.cells));
		assertTrue(this.world.get(cell(0, 64, 0)).getValue(DoorBlock.OPEN));
		assertTrue(this.world.get(cell(0, 65, 0)).getValue(DoorBlock.OPEN));
		assertNull(this.registry.state(HOST, 99, Device.DOOR_OPENED, 16, this.cells), "an unknown id changes nothing");
		assertNull(this.registry.state(OTHER, 1, Device.DOOR_CLOSED, 16, this.cells), "ids are scoped by owner");
		assertTrue(this.world.get(cell(0, 64, 0)).getValue(DoorBlock.OPEN));
	}

	@Test
	void aLockedDoorShowsItsLampAndLosesItWhenUnlocked() {
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_LOCKED, Device.LOCKED | 16)), 0, this.cells);
		assertEquals(Blocks.REDSTONE_LAMP, this.world.get(cell(0, 66, 0)).getBlock());
		this.registry.state(HOST, 1, Device.DOOR_CLOSED, 16, this.cells);
		assertTrue(this.world.get(cell(0, 66, 0)).isAir(), "the lamp went with the lock");
	}

	@Test
	void aPlayersBlockWhereTheLockLampGoesLeavesOnlyTheLampOut() {
		this.world.put(cell(0, 66, 0), Blocks.STONE.defaultBlockState());
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_LOCKED, Device.LOCKED | 16)), 0, this.cells);
		assertEquals(Blocks.OAK_DOOR, this.world.get(cell(0, 64, 0)).getBlock());
		assertEquals(Blocks.STONE, this.world.get(cell(0, 66, 0)).getBlock());
	}

	@Test
	void aSecureDoorIsIronAndAMovedDeviceMovesItsBlocks() {
		this.registry.update(HOST, List.of(door(1, 0, Device.DOOR_CLOSED, Device.SECURE | 16)), 0, this.cells);
		assertEquals(Blocks.IRON_DOOR, this.world.get(cell(0, 64, 0)).getBlock());
		this.registry.update(HOST, List.of(door(1, 3, Device.DOOR_CLOSED, Device.SECURE | 16)), 20, this.cells);
		assertTrue(this.world.get(cell(0, 64, 0)).isAir());
		assertEquals(Blocks.IRON_DOOR, this.world.get(cell(3, 64, 0)).getBlock());
		assertFalse(this.registry.excluded(cell(0, 64, 0)), "the old box is free for the replica again");
	}

	@Test
	void devicesGoTenSecondsAfterTheirLastListAndAtSessionEnd() {
		this.registry.update(HOST, List.of(door(1, 0, 0, 16), door(2, 4, 0, 16)), 0, this.cells);
		this.registry.update(OTHER, List.of(door(3, 8, 0, 16)), 0, this.cells);
		// only door 2 is named again (the full list every 5 s keeps the devices within 48 m alive)
		this.registry.update(HOST, List.of(door(2, 4, 0, 16)), 100, this.cells);
		assertEquals(0, this.registry.expire(DeviceRules.GONE_TICKS - 1, this.cells).removed());
		DeviceRegistry.Change gone = this.registry.expire(DeviceRules.GONE_TICKS, this.cells);
		assertEquals(2, gone.removed(), "door 1 and the other host's door 3");
		assertTrue(this.world.get(cell(0, 64, 0)).isAir());
		assertEquals(Blocks.OAK_DOOR, this.world.get(cell(4, 64, 0)).getBlock());
		assertEquals(1, this.registry.end(HOST, this.cells).removed());
		assertTrue(this.world.get(cell(4, 64, 0)).isAir());
		assertEquals(0, this.registry.size());
		assertFalse(this.registry.excluded(cell(4, 64, 0)));
	}

	@Test
	void aBlockAPlayerPutInTheCellSinceIsNeverRemoved() {
		this.registry.update(HOST, List.of(door(1, 0, 0, 16)), 0, this.cells);
		this.registry.broken(cell(0, 64, 0)); // creative broke the lower half, then built there
		this.registry.broken(cell(0, 65, 0));
		this.world.put(cell(0, 64, 0), Blocks.STONE.defaultBlockState());
		this.world.put(cell(0, 65, 0), Blocks.AIR.defaultBlockState());
		this.registry.end(HOST, this.cells);
		assertEquals(Blocks.STONE, this.world.get(cell(0, 64, 0)).getBlock());
	}
}
