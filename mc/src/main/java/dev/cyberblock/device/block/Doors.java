package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Device doors for the server (4M): both halves, opening and closing with the vanilla sound, and the lock lamp above. Every call takes
 * the door's lower cell. Server thread. Cells are taken only when {@link DeviceBlocks#free free} (never over a player's block).
 */
public final class Doors {
	private Doors() {
	}

	/** Places a door (iron when {@code secure}); false (nothing placed) when either cell is taken. */
	public static boolean place(final ServerLevel level, final BlockPos lower, final Direction facing, final DoorHingeSide hinge, final boolean secure,
		final boolean open) {
		if (!DeviceBlocks.free(level.getBlockState(lower)) || !DeviceBlocks.free(level.getBlockState(lower.above()))) {
			return false;
		}

		BlockState door = (secure ? DeviceBlocks.IRON_DOOR : DeviceBlocks.DOOR).defaultBlockState().setValue(DoorBlock.FACING, facing.getAxis().isHorizontal()
			? facing : Direction.NORTH).setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.OPEN, open);
		level.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
		level.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		return true;
	}

	/** Opens or closes the door at {@code lower} (both halves follow), with the vanilla sound; false when no device door is there. */
	public static boolean setOpen(final ServerLevel level, final BlockPos lower, final boolean open) {
		BlockState state = level.getBlockState(lower);
		if (!(state.getBlock() instanceof DeviceDoorBlock door)) {
			return false;
		}

		door.setOpen(null, level, state, lower, open);
		return true;
	}

	/**
	 * The door's lock: locked (or quest-locked) puts the lamp on the cell above the door (red, or grey and dark when quest-locked) and
	 * greys a quest-locked door; unlocked takes the lamp away. False when no device door is there.
	 */
	public static boolean setLocked(final ServerLevel level, final BlockPos lower, final boolean locked, final boolean questLocked) {
		BlockState state = level.getBlockState(lower);
		if (!(state.getBlock() instanceof DeviceDoorBlock)) {
			return false;
		}

		for (BlockPos half : new BlockPos[] {lower, lower.above()}) {
			BlockState s = level.getBlockState(half);
			if (s.getBlock() instanceof DeviceDoorBlock && s.getValue(DeviceBlock.QUEST_LOCKED) != questLocked) {
				level.setBlock(half, s.setValue(DeviceBlock.QUEST_LOCKED, questLocked), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}

		BlockPos lampPos = lower.above(2);
		BlockState lamp = level.getBlockState(lampPos);
		if (locked || questLocked) {
			if (DeviceBlocks.free(lamp) || lamp.is(DeviceBlocks.DOOR_LOCK_LAMP)) {
				level.setBlock(lampPos, DeviceBlocks.DOOR_LOCK_LAMP.defaultBlockState().setValue(DeviceBlock.QUEST_LOCKED, questLocked), Block.UPDATE_ALL);
			}
		} else if (lamp.is(DeviceBlocks.DOOR_LOCK_LAMP)) {
			level.setBlock(lampPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}

		return true;
	}

	/** Removes the door at {@code lower} and its lock lamp (only device blocks are touched). */
	public static void remove(final ServerLevel level, final BlockPos lower) {
		if (level.getBlockState(lower.above(2)).is(DeviceBlocks.DOOR_LOCK_LAMP)) {
			level.setBlock(lower.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}

		for (BlockPos half : new BlockPos[] {lower.above(), lower}) {
			if (level.getBlockState(half).getBlock() instanceof DeviceDoorBlock) {
				level.setBlock(half, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}
}
