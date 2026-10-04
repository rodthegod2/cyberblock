package dev.cyberblock.device;

import dev.cyberblock.net.Permissions.DeviceType;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Where a device's blocks go (megaplan 4-M1), pure: the cells and states of its Minecraft version, and its box (the cells the replica
 * keeps out of, ReplicaRules.excluded). The device's position is its base; its yaw, snapped to the nearest side, is its facing; the
 * width (device_map size w) runs across the facing, the depth (d) along it. Layouts per type:
 * <ul>
 * <li>door: two blocks high; 2 m wide or more is a double door whose hinges sit on the outer sides, so it opens apart;</li>
 * <li>gate, screen, light: a panel w wide and h high (a gate at most 4 high); a plain gate block is gone while open, 4B's sliding gate
 * stays and shows its step;</li>
 * <li>elevator: a floor w by d at its base;</li>
 * <li>every other type: one block at its base cell.</li>
 * </ul>
 * The device_map attachments (the red lamp over a locked door, the lift's call button) go at their offset from the first cell (dx across
 * the facing, dy up, dz along it).
 */
public final class DeviceShape {
	/** A gate never stands taller than this, in blocks. */
	static final int GATE_MAX_HEIGHT = 4;
	private static final double EDGE = 1.0e-3;

	/** The block states a device is drawn from: its own (never air), and one per device_map attachment, in order (air: left out). */
	public record Blocks(BlockState block, List<BlockState> attachments) {
	}

	private DeviceShape() {
	}

	public static Direction facing(final Device d) {
		return Direction.fromYRot(d.yaw());
	}

	/** The cells (packed BlockPos, placement order) and the states the device wants now. */
	public static Map<Long, BlockState> cells(final Device d, final DeviceMap.Entry e, final Blocks b) {
		Map<Long, BlockState> out = new LinkedHashMap<>();
		BlockState look = DeviceBlocks.look(b.block(), d);
		Direction facing = facing(d), across = facing.getClockWise();
		int y = (int) Math.floor(d.y());
		BlockPos first = BlockPos.containing(d.x(), d.y(), d.z());
		switch (d.type()) {
			case DOOR -> {
				int doors = e.w() >= 2 ? 2 : 1;
				first = along(d, across, doors, 0, y);
				for (int i = 0; i < doors; i++) {
					BlockPos base = along(d, across, doors, i, y);
					if (look.getBlock() instanceof DoorBlock) {
						// the counter-clockwise door hinges on its left, the clockwise one on its right: a double door opens apart
						BlockState door = look.setValue(DoorBlock.HINGE, i == 1 ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT);
						out.put(base.asLong(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
						out.put(base.above().asLong(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
					} else {
						out.put(base.asLong(), look);
						out.put(base.above().asLong(), look);
					}
				}
			}
			case GATE, SCREEN, LIGHT -> {
				int height = d.type() == DeviceType.GATE ? Math.min(e.h(), GATE_MAX_HEIGHT) : e.h();
				first = along(d, across, e.w(), 0, y);
				if (d.type() == DeviceType.GATE && d.open() && !DeviceBlocks.showsOpen(look.getBlock())) {
					break; // a plain gate block slides away: nothing stands while it is open
				}

				for (int i = 0; i < e.w(); i++) {
					BlockPos base = along(d, across, e.w(), i, y);
					for (int up = 0; up < height; up++) {
						out.put(base.above(up).asLong(), look);
					}
				}
			}
			case ELEVATOR -> {
				first = along(d, across, e.w(), 0, y).relative(facing, -(e.d() - 1) / 2);
				for (int i = 0; i < e.w(); i++) {
					BlockPos row = along(d, across, e.w(), i, y).relative(facing, -(e.d() - 1) / 2);
					for (int k = 0; k < e.d(); k++) {
						out.put(row.relative(facing, k).asLong(), look);
					}
				}
			}
			default -> out.put(first.asLong(), look);
		}

		for (int i = 0; i < e.attachments().size() && i < b.attachments().size(); i++) {
			DeviceMap.Attachment a = e.attachments().get(i);
			if (a.lockedOnly() && !d.locked() || b.attachments().get(i).isAir()) {
				continue;
			}

			BlockState s = DeviceBlocks.attachment(b.attachments().get(i), d, a.lockedOnly());
			out.putIfAbsent(first.relative(across, a.dx()).above(a.dy()).relative(facing, a.dz()).asLong(), s);
		}

		return out;
	}

	/** Cell i of n placed side by side across the facing, centred on the device's position, at height y. */
	private static BlockPos along(final Device d, final Direction across, final int n, final int i, final int y) {
		double offset = i - (n - 1) / 2.0;
		return new BlockPos((int) Math.floor(d.x() + across.getStepX() * offset), y, (int) Math.floor(d.z() + across.getStepZ() * offset));
	}

	/**
	 * The device's box: its size from device_map (w across the facing, h up from its base, d along the facing), centred on its
	 * position, in whole cells; at least the base cell.
	 */
	public static LongSet box(final Device d, final DeviceMap.Entry e) {
		boolean widthOnX = facing(d).getAxis() == Direction.Axis.Z;
		int sx = widthOnX ? e.w() : e.d(), sz = widthOnX ? e.d() : e.w();
		int x0 = lo(d.x(), sx), x1 = hi(d.x(), sx), z0 = lo(d.z(), sz), z1 = hi(d.z(), sz);
		int y0 = (int) Math.floor(d.y()), y1 = y0 + Math.max(1, e.h()) - 1;
		LongSet out = new LongLinkedOpenHashSet();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					out.add(BlockPos.asLong(x, y, z));
				}
			}
		}

		return out;
	}

	private static int lo(final double c, final int size) {
		return size <= 1 ? (int) Math.floor(c) : (int) Math.floor(c - size / 2.0 + EDGE);
	}

	private static int hi(final double c, final int size) {
		return size <= 1 ? (int) Math.floor(c) : (int) Math.floor(c + size / 2.0 - EDGE);
	}
}
