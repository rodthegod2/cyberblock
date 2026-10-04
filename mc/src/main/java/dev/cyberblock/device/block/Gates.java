package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sliding gates for the server (4M). A gate is {@code width} columns of {@code height} {@link SlidingGateBlock} cells facing
 * {@code facing}; {@code origin} is the bottom cell of the column on the side the gate slides into ({@code facing.getCounterClockWise()}),
 * the other columns follow along {@code facing.getClockWise()}. The gate is open by quarter blocks: opening moves it a quarter toward
 * its origin every {@link DeviceMotions#STEP_TICKS} (the far column empties first), closing moves it back; an open column keeps only its
 * floor rail. Cells are only placed into {@link DeviceBlocks#free free} cells. Server thread.
 */
public final class Gates {
	public static final int MAX_SIZE = 16;
	private static final int Q = SlidingGateBlock.OPEN_STEP;

	/** One gate's cells. */
	public record Gate(BlockPos origin, Direction facing, int width, int height) {
		public Gate {
			if (!facing.getAxis().isHorizontal() || width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE) {
				throw new IllegalArgumentException("bad gate " + facing + " " + width + "x" + height);
			}

			origin = origin.immutable();
		}

		BlockPos cell(final int column, final int row) {
			return this.origin.relative(this.facing.getClockWise(), column).above(row);
		}
	}

	private Gates() {
	}

	/** Puts the gate up at once, closed or open (the floor rail only); returns the bottom cells placed. */
	public static int place(final ServerLevel level, final Gate gate, final boolean open) {
		DeviceMotions.stop(key(level, gate));
		int placed = 0;
		for (int column = 0; column < gate.width(); column++) {
			BlockPos pos = gate.cell(column, 0);
			if (DeviceBlocks.free(level.getBlockState(pos)) && level.setBlock(pos, cell(gate, open ? Q : 0), Block.UPDATE_ALL)) {
				placed++;
			}
		}

		set(level, gate, open ? Q * gate.width() : 0);
		return placed;
	}

	/** Slides the gate open or closed, a quarter block per step, from where it is now. */
	public static void slide(final ServerLevel level, final Gate gate, final boolean open) {
		DeviceMotions.start(key(level, gate), () -> step(level, gate, open));
	}

	/** Takes every cell of the gate away (stops its motion). */
	public static void remove(final ServerLevel level, final Gate gate) {
		DeviceMotions.stop(key(level, gate));
		for (int column = 0; column < gate.width(); column++) {
			for (int row = 0; row < gate.height(); row++) {
				BlockPos pos = gate.cell(column, row);
				if (level.getBlockState(pos).is(DeviceBlocks.SLIDING_GATE)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** How open the gate is now, in quarter blocks (0 closed .. 4 x width open); a missing cell counts as open. */
	public static int opened(final ServerLevel level, final Gate gate) {
		int q = 0;
		for (int column = 0; column < gate.width(); column++) {
			BlockState s = level.getBlockState(gate.cell(column, 0));
			q += s.is(DeviceBlocks.SLIDING_GATE) ? s.getValue(SlidingGateBlock.STEP) : Q;
		}

		return q;
	}

	/** One step further: a quarter more open, or a quarter more closed, within 0..4 x width. Pure. */
	static int next(final int opened, final int width, final boolean open) {
		return open ? Math.min(opened + 1, Q * width) : Math.max(opened - 1, 0);
	}

	/** A column's step when the gate is {@code opened} quarters open: the far column empties first. Pure. */
	static int stepOf(final int opened, final int width, final int column) {
		return Math.clamp(opened - Q * (width - 1 - column), 0, Q);
	}

	private static boolean step(final ServerLevel level, final Gate gate, final boolean open) {
		int now = opened(level, gate);
		int next = next(now, gate.width(), open);
		if (next == now) {
			return false;
		}

		set(level, gate, next);
		level.playSound(null, gate.cell(gate.width() / 2, 0), open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS,
			0.4F, 0.6F + 0.1F * (next % Q));
		return next != (open ? Q * gate.width() : 0);
	}

	/**
	 * Every cell to its step for {@code opened}. Every cell of the art has a floor rail, so a column that is fully open keeps only its
	 * bottom cell (the rail on the ground): the cells above go, and come back as it closes. Cells that are not the gate's (a player's
	 * block in the way) are left alone.
	 */
	private static void set(final ServerLevel level, final Gate gate, final int opened) {
		for (int column = 0; column < gate.width(); column++) {
			int step = stepOf(opened, gate.width(), column);
			for (int row = 0; row < gate.height(); row++) {
				BlockPos pos = gate.cell(column, row);
				BlockState s = level.getBlockState(pos);
				if (row > 0 && step == Q) {
					if (s.is(DeviceBlocks.SLIDING_GATE)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					}
				} else if (s.is(DeviceBlocks.SLIDING_GATE) ? s.getValue(SlidingGateBlock.STEP) != step : row > 0 && DeviceBlocks.free(s)) {
					level.setBlock(pos, cell(gate, step), Block.UPDATE_ALL);
				}
			}
		}
	}

	private static BlockState cell(final Gate gate, final int step) {
		return DeviceBlocks.SLIDING_GATE.defaultBlockState().setValue(FacingDeviceBlock.FACING, gate.facing()).setValue(SlidingGateBlock.STEP, step);
	}

	private static String key(final ServerLevel level, final Gate gate) {
		return DeviceMotions.key("gate", level, gate.origin());
	}
}
