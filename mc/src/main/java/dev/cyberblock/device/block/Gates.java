package dev.cyberblock.device.block;

import dev.cyberblock.Cyberblock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sliding gates for the server (4M). A gate is {@code width} columns of {@code height} panels, from {@code origin} (the bottom cell of
 * the column on the side the gate slides into) along {@code along}. Opening slides it into its origin side one column per step
 * ({@link DeviceMotions#STEP_TICKS}): the far column goes first; closing slides it back out. Panels are only placed into
 * {@link DeviceBlocks#free free} cells. Server thread.
 */
public final class Gates {
	public static final int MAX_SIZE = 16;

	/** One gate's cells. */
	public record Gate(BlockPos origin, Direction along, int width, int height) {
		public Gate {
			if (!along.getAxis().isHorizontal() || width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE) {
				throw new IllegalArgumentException("bad gate " + along + " " + width + "x" + height);
			}

			origin = origin.immutable();
		}

		BlockPos cell(final int column, final int row) {
			return this.origin.relative(this.along, column).above(row);
		}
	}

	private Gates() {
	}

	/** Puts the gate up at once, closed (every panel) or open (none); returns the panels placed. */
	public static int place(final ServerLevel level, final Gate gate, final boolean open) {
		DeviceMotions.stop(key(level, gate));
		if (open) {
			clear(level, gate, 0);
			return 0;
		}

		int placed = 0;
		for (int column = 0; column < gate.width(); column++) {
			placed += fill(level, gate, column);
		}

		return placed;
	}

	/** Slides the gate open or closed, one column per step, from where it is now. */
	public static void slide(final ServerLevel level, final Gate gate, final boolean open) {
		DeviceMotions.start(key(level, gate), () -> step(level, gate, open));
	}

	/** Takes every panel of the gate away (stops its motion). */
	public static void remove(final ServerLevel level, final Gate gate) {
		DeviceMotions.stop(key(level, gate));
		clear(level, gate, 0);
	}

	/** How many columns stand now, counted from the origin side. */
	public static int closedColumns(final ServerLevel level, final Gate gate) {
		int closed = 0;
		while (closed < gate.width() && level.getBlockState(gate.cell(closed, 0)).is(DeviceBlocks.SLIDING_GATE)) {
			closed++;
		}

		return closed;
	}

	/** The column count after one step: one fewer while opening, one more while closing, within 0..width. Pure. */
	static int next(final int closed, final int width, final boolean open) {
		return open ? Math.max(closed - 1, 0) : Math.min(closed + 1, width);
	}

	private static boolean step(final ServerLevel level, final Gate gate, final boolean open) {
		int closed = closedColumns(level, gate);
		int next = next(closed, gate.width(), open);
		if (next == closed) {
			return false;
		}

		if (open) {
			clear(level, gate, closed - 1);
		} else if (fill(level, gate, closed) < gate.height()) {
			Cyberblock.LOG.info("device: gate at {} stopped at {} of {} columns (blocked)", gate.origin().toShortString(), closed, gate.width());
			return false;
		}

		level.playSound(null, gate.cell(open ? closed - 1 : closed, 0), open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE,
			SoundSource.BLOCKS, 0.6F, 0.7F);
		return next != (open ? 0 : gate.width());
	}

	/** Panels into the free cells of one column; returns how many stand there now. */
	private static int fill(final ServerLevel level, final Gate gate, final int column) {
		BlockState panel = DeviceBlocks.SLIDING_GATE.defaultBlockState().setValue(SlidingGateBlock.AXIS, gate.along().getAxis());
		int standing = 0;
		for (int row = 0; row < gate.height(); row++) {
			BlockPos cell = gate.cell(column, row);
			BlockState now = level.getBlockState(cell);
			if (now.is(DeviceBlocks.SLIDING_GATE) || DeviceBlocks.free(now) && level.setBlock(cell, panel, Block.UPDATE_ALL)) {
				standing++;
			}
		}

		return standing;
	}

	/** Takes the panels of the columns from {@code fromColumn} on away. */
	private static void clear(final ServerLevel level, final Gate gate, final int fromColumn) {
		for (int column = fromColumn; column < gate.width(); column++) {
			for (int row = 0; row < gate.height(); row++) {
				BlockPos cell = gate.cell(column, row);
				if (level.getBlockState(cell).is(DeviceBlocks.SLIDING_GATE)) {
					level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	private static String key(final ServerLevel level, final Gate gate) {
		return DeviceMotions.key("gate", level, gate.origin());
	}
}
