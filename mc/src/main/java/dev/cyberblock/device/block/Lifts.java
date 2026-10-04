package dev.cyberblock.device.block;

import dev.cyberblock.Cyberblock;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Lift platforms for the server (4M). A {@link Shaft} is the car's {@code width x depth} footprint from (x, z) (its smallest corner)
 * between {@code minY} and {@code maxY}; the platform is one layer of {@link LiftPlatformBlock}s in it. {@link #moveTo} moves the
 * layer one block per step ({@link DeviceMotions#STEP_TICKS}) to the floor Night City reports, carrying whoever stands on it.
 * Platform cells are only taken when {@link DeviceBlocks#free free}. Server thread.
 */
public final class Lifts {
	public static final int MAX_SIZE = 8;
	public static final int MAX_HEIGHT = 384;

	/** One lift shaft. */
	public record Shaft(int x, int z, int width, int depth, int minY, int maxY) {
		public Shaft {
			if (width < 1 || width > MAX_SIZE || depth < 1 || depth > MAX_SIZE || minY > maxY || maxY - minY > MAX_HEIGHT) {
				throw new IllegalArgumentException("bad lift shaft " + width + "x" + depth + " y " + minY + ".." + maxY);
			}
		}

		BlockPos anchor() {
			return new BlockPos(this.x, this.minY, this.z);
		}

		boolean holds(final int y) {
			return y >= this.minY && y <= this.maxY;
		}
	}

	private Lifts() {
	}

	/** Puts the platform at {@code y} at once (any other platform layer in the shaft goes); false when {@code y} is outside the shaft. */
	public static boolean place(final ServerLevel level, final Shaft shaft, final int y) {
		DeviceMotions.stop(key(level, shaft));
		if (!shaft.holds(y)) {
			return false;
		}

		platformY(level, shaft).ifPresent(old -> layer(level, shaft, old, null));
		layer(level, shaft, y, false);
		return true;
	}

	/** The platform's layer now, or empty when the shaft has none. */
	public static OptionalInt platformY(final ServerLevel level, final Shaft shaft) {
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int y = shaft.minY(); y <= shaft.maxY(); y++) {
			if (level.getBlockState(cell.set(shaft.x(), y, shaft.z())).is(DeviceBlocks.LIFT_PLATFORM)) {
				return OptionalInt.of(y);
			}
		}

		return OptionalInt.empty();
	}

	/** Moves the platform to {@code targetY} (clamped into the shaft), one block per step. */
	public static void moveTo(final ServerLevel level, final Shaft shaft, final int targetY) {
		int target = Math.clamp(targetY, shaft.minY(), shaft.maxY());
		DeviceMotions.start(key(level, shaft), () -> step(level, shaft, target));
	}

	/** Is the platform on its way? */
	public static boolean moving(final ServerLevel level, final Shaft shaft) {
		return DeviceMotions.running(key(level, shaft));
	}

	/** Takes the platform away (stops its motion). */
	public static void remove(final ServerLevel level, final Shaft shaft) {
		DeviceMotions.stop(key(level, shaft));
		platformY(level, shaft).ifPresent(y -> layer(level, shaft, y, null));
	}

	/** The layer after one step toward {@code target}. Pure. */
	static int next(final int y, final int target) {
		return y + Integer.signum(target - y);
	}

	private static boolean step(final ServerLevel level, final Shaft shaft, final int target) {
		OptionalInt now = platformY(level, shaft);
		if (now.isEmpty()) {
			return false;
		}

		int y = now.getAsInt();
		int to = next(y, target);
		if (to == y) {
			layer(level, shaft, y, false);
			return false;
		}

		if (!layerFree(level, shaft, to)) {
			Cyberblock.LOG.info("device: lift at {} {} stopped at y {} (blocked)", shaft.x(), shaft.z(), y);
			layer(level, shaft, y, false);
			return false;
		}

		int dy = to - y;
		var riders = level.getEntities((Entity) null, new AABB(shaft.x(), y + LiftPlatformBlock.HEIGHT - 0.05, shaft.z(), shaft.x() + shaft.width(), y + 2.5, shaft.z() + shaft.depth()),
			e -> e.isAlive() && !e.isSpectator() && !(e instanceof HangingEntity));
		if (dy > 0) {
			ride(riders, dy);
		}

		layer(level, shaft, to, to != target);
		layer(level, shaft, y, null);
		if (dy < 0) {
			ride(riders, dy);
		}

		return to != target;
	}

	private static void ride(final Iterable<Entity> riders, final int dy) {
		for (Entity e : riders) {
			e.teleportTo(e.getX(), e.getY() + dy, e.getZ());
			e.resetFallDistance();
		}
	}

	private static boolean layerFree(final ServerLevel level, final Shaft shaft, final int y) {
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < shaft.width(); dx++) {
			for (int dz = 0; dz < shaft.depth(); dz++) {
				var state = level.getBlockState(cell.set(shaft.x() + dx, y, shaft.z() + dz));
				if (!DeviceBlocks.free(state) && !state.is(DeviceBlocks.LIFT_PLATFORM)) {
					return false;
				}
			}
		}

		return true;
	}

	/** Sets one layer to the platform (moving or not), or clears it ({@code moving} null: air, only where a platform is). */
	private static void layer(final ServerLevel level, final Shaft shaft, final int y, final @Nullable Boolean moving) {
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < shaft.width(); dx++) {
			for (int dz = 0; dz < shaft.depth(); dz++) {
				cell.set(shaft.x() + dx, y, shaft.z() + dz);
				BlockState state = level.getBlockState(cell);
				BlockState platform = moving == null ? null : DeviceBlocks.LIFT_PLATFORM.defaultBlockState().setValue(LiftPlatformBlock.MOVING, moving);
				if (platform != null && (DeviceBlocks.free(state) || state.is(DeviceBlocks.LIFT_PLATFORM) && state != platform)) {
					level.setBlock(cell, platform, Block.UPDATE_ALL);
				} else if (platform == null && state.is(DeviceBlocks.LIFT_PLATFORM)) {
					level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	private static String key(final ServerLevel level, final Shaft shaft) {
		return DeviceMotions.key("lift", level, shaft.anchor());
	}
}
