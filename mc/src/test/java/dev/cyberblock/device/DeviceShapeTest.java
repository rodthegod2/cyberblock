package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: where a device's blocks go, how its state shows, and its box. */
class DeviceShapeTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static Device door(final double x, final double z, final float yaw, final int st, final int fl) {
		return new Device(1, true, "Door", DeviceType.DOOR, x, 64.0, z, yaw, st, fl);
	}

	private static DeviceMap.Entry entry(final DeviceType type, final int w, final int h, final int d, final List<DeviceMap.Attachment> att) {
		return new DeviceMap.Entry(type, null, "minecraft:stone", null, null, w, h, d, att, List.of());
	}

	private static final DeviceMap.Attachment LOCK = new DeviceMap.Attachment("minecraft:redstone_lamp", 0, 2, 0, true);

	@Test
	void aDoorIsTwoHalvesFacingItsYawWithTheLockLampOnlyWhileLocked() {
		DeviceShape.Blocks b = new DeviceShape.Blocks(Blocks.OAK_DOOR.defaultBlockState(), List.of(Blocks.REDSTONE_LAMP.defaultBlockState()));
		Map<Long, BlockState> closed = DeviceShape.cells(door(10.5, 20.5, 0, Device.DOOR_CLOSED, 0), entry(DeviceType.DOOR, 1, 2, 1, List.of(LOCK)), b);
		assertEquals(2, closed.size());
		BlockState lower = closed.get(new BlockPos(10, 64, 20).asLong()), upper = closed.get(new BlockPos(10, 65, 20).asLong());
		assertEquals(DoubleBlockHalf.LOWER, lower.getValue(DoorBlock.HALF));
		assertEquals(DoubleBlockHalf.UPPER, upper.getValue(DoorBlock.HALF));
		assertEquals(Direction.SOUTH, lower.getValue(DoorBlock.FACING), "yaw 0 faces south (Minecraft yaw)");
		assertFalse(lower.getValue(DoorBlock.OPEN));

		Map<Long, BlockState> open = DeviceShape.cells(door(10.5, 20.5, 0, Device.DOOR_OPENED, 0), entry(DeviceType.DOOR, 1, 2, 1, List.of(LOCK)), b);
		assertTrue(open.get(new BlockPos(10, 64, 20).asLong()).getValue(DoorBlock.OPEN));

		Map<Long, BlockState> locked = DeviceShape.cells(door(10.5, 20.5, 0, Device.DOOR_LOCKED, Device.LOCKED), entry(DeviceType.DOOR, 1, 2, 1, List.of(LOCK)), b);
		BlockState lamp = locked.get(new BlockPos(10, 66, 20).asLong());
		assertEquals(Blocks.REDSTONE_LAMP, lamp.getBlock(), "the red lamp above a locked door");
		assertTrue(lamp.getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT));
	}

	@Test
	void aDoubleDoorOpensApart() {
		DeviceShape.Blocks b = new DeviceShape.Blocks(Blocks.IRON_DOOR.defaultBlockState(), List.of());
		// facing north (yaw 180): across is east, so the doors stand at x 9 and 10
		Map<Long, BlockState> cells = DeviceShape.cells(door(10.0, 5.5, 180, Device.DOOR_CLOSED, Device.SECURE), entry(DeviceType.DOOR, 2, 2, 1, List.of()), b);
		assertEquals(4, cells.size());
		assertEquals(DoorHingeSide.LEFT, cells.get(new BlockPos(9, 64, 5).asLong()).getValue(DoorBlock.HINGE));
		assertEquals(DoorHingeSide.RIGHT, cells.get(new BlockPos(10, 64, 5).asLong()).getValue(DoorBlock.HINGE));
		assertEquals(Direction.NORTH, cells.get(new BlockPos(9, 64, 5).asLong()).getValue(DoorBlock.FACING));
	}

	@Test
	void aPlainGateSlidesAwayWhileOpenAndAPanelFillsItsSize() {
		DeviceShape.Blocks bars = new DeviceShape.Blocks(Blocks.IRON_BARS.defaultBlockState(), List.of());
		Device closed = new Device(2, true, "SecurityGate", DeviceType.GATE, 0.5, 64, 0.5, 0, Device.ST_OFF, 0);
		Map<Long, BlockState> cells = DeviceShape.cells(closed, entry(DeviceType.GATE, 3, 6, 1, List.of()), bars);
		assertEquals(3 * DeviceShape.GATE_MAX_HEIGHT, cells.size(), "3 wide, at most 4 high");
		assertTrue(DeviceShape.cells(closed.withState(Device.ST_ON, 0), entry(DeviceType.GATE, 3, 2, 1, List.of()), bars).isEmpty());
		Device screen = new Device(3, true, "BillboardDevice", DeviceType.SCREEN, 0.5, 70, 0.5, 90, Device.ST_ON, 16);
		assertEquals(12, DeviceShape.cells(screen, entry(DeviceType.SCREEN, 4, 3, 1, List.of()), new DeviceShape.Blocks(Blocks.COAL_BLOCK.defaultBlockState(), List.of())).size());
	}

	@Test
	void aLiftIsAFloorAndItsButtonAnAttachment() {
		Device lift = new Device(4, true, "LiftDevice", DeviceType.ELEVATOR, 0.0, 64, 0.0, 0, Device.ST_OFF, 16);
		DeviceMap.Attachment button = new DeviceMap.Attachment("minecraft:stone_button", 0, 1, 0, false);
		Map<Long, BlockState> cells = DeviceShape.cells(lift, entry(DeviceType.ELEVATOR, 2, 1, 2, List.of(button)),
			new DeviceShape.Blocks(Blocks.IRON_BLOCK.defaultBlockState(), List.of(Blocks.SMOOTH_STONE.defaultBlockState())));
		assertEquals(5, cells.size(), "a 2 x 2 floor and the button");
		assertEquals(4, cells.values().stream().filter(s -> s.is(Blocks.IRON_BLOCK)).count());
	}

	@Test
	void stateShowsThroughThePropertiesABlockHas() {
		Device lamp = new Device(5, true, "ElectricLight", DeviceType.LIGHT, 0.5, 64, 0.5, 0, Device.ST_ON, 16);
		BlockState lit = DeviceBlocks.look(Blocks.REDSTONE_LAMP.defaultBlockState(), lamp);
		assertTrue(lit.getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT));
		assertFalse(DeviceBlocks.look(Blocks.REDSTONE_LAMP.defaultBlockState(), lamp.withState(Device.ST_OFF, 16))
			.getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT));
		assertSame(Blocks.STONE.defaultBlockState(), DeviceBlocks.look(Blocks.STONE.defaultBlockState(), lamp), "no such properties: unchanged");
		assertEquals(Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, Direction.WEST),
			DeviceBlocks.look(Blocks.DISPENSER.defaultBlockState(), new Device(6, true, "SecurityTurret", DeviceType.TURRET, 0, 64, 0, 90, 1, 16)));
	}

	@Test
	void theBoxIsTheSizeAroundThePositionAndNeverEmpty() {
		Device d = door(10.0, 20.5, 0, 0, 0); // facing south: the width runs along x
		assertEquals(2 * 3 * 1, DeviceShape.box(d, entry(DeviceType.DOOR, 2, 3, 1, List.of())).size());
		assertTrue(DeviceShape.box(d, entry(DeviceType.DOOR, 2, 3, 1, List.of())).contains(new BlockPos(9, 66, 20).asLong()));
		assertEquals(1, DeviceShape.box(new Device(7, true, "TV", DeviceType.SCREEN, 3.2, 64.9, 3.7, 0, 1, 0), entry(DeviceType.SCREEN, 1, 1, 1, List.of())).size());
	}
}
