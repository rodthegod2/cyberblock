package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Gates, garage doors and road barriers (section 1.11, toggle bit 2 {@code gate}): one cell of a multi-block sliding gate (4A's art):
 * a floor rail and a 4 px panel across the cell, standing across {@link #FACING} (the gate spans {@code facing.getClockWise()}).
 * {@link #STEP} 0 is a whole panel, each step slides a quarter of it toward {@code facing.getCounterClockWise()}, 4 leaves only the
 * rail (open). {@link Gates} steps a whole gate so it slides as one; a right-click asks Night City to {@code open} a closed gate or
 * {@code close} an open one.
 */
public final class SlidingGateBlock extends FacingDeviceBlock {
	public static final int OPEN_STEP = 4;
	public static final IntegerProperty STEP = IntegerProperty.create("step", 0, OPEN_STEP);
	/** [facing 2D index][step]: the rail and what is left of the panel. */
	private static final VoxelShape[][] SHAPES = new VoxelShape[4][OPEN_STEP + 1];

	static {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			for (int step = 0; step <= OPEN_STEP; step++) {
				VoxelShape rail = box(facing, 0, 0, 5, 16, 1, 11);
				SHAPES[facing.get2DDataValue()][step] = step == OPEN_STEP ? rail : Shapes.or(rail, box(facing, 0, 1, 6, 16 - 4 * step, 16, 10));
			}
		}
	}

	SlidingGateBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STEP, 0).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(STEP);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(STEP)];
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return DeviceHooks.act(state, level, pos, player, state.getValue(STEP) < OPEN_STEP ? "open" : "close");
	}

	/**
	 * A gate always slides: when the server sets a cell more than one step away (4M sets the open or closed step directly), the cell
	 * takes one step now and the rest one per {@link DeviceMotions#STEP_TICKS}. A one-step change ({@link Gates}' own slide) is taken as
	 * it is and ends any slide of that cell. The cell's own steps skip this ({@link Block#UPDATE_SKIP_ON_PLACE}).
	 */
	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState old, final boolean movedByPiston) {
		super.onPlace(state, level, pos, old, movedByPiston);
		if (!(level instanceof ServerLevel server) || !old.is(this) || old.getValue(FACING) != state.getValue(FACING)) {
			return;
		}

		String key = DeviceMotions.key("gate cell", server, pos);
		int target = state.getValue(STEP);
		if (Math.abs(target - old.getValue(STEP)) <= 1) {
			DeviceMotions.stop(key);
			return;
		}

		BlockPos at = pos.immutable();
		step(server, at, old.getValue(STEP), target);
		DeviceMotions.start(key, () -> {
			BlockState now = server.getBlockState(at);
			return now.is(this) && step(server, at, now.getValue(STEP), target);
		});
	}

	/** One step of a cell from {@code from} toward {@code target}; true while more steps are left. */
	private boolean step(final ServerLevel level, final BlockPos pos, final int from, final int target) {
		if (from == target) {
			return false;
		}

		int next = from + Integer.signum(target - from);
		BlockState state = level.getBlockState(pos);
		level.setBlock(pos, state.setValue(STEP, next), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
		// one sound per gate: from its bottom cell at the far end
		if (!level.getBlockState(pos.below()).is(this) && !level.getBlockState(pos.relative(state.getValue(FACING).getClockWise())).is(this)) {
			level.playSound(null, pos, next > from ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.4F,
				0.6F + 0.1F * next);
		}

		return next != target;
	}

	/** A box of the north-facing model (pixels) turned to {@code facing}, as the blockstate's y rotation turns the model. */
	static VoxelShape box(final Direction facing, final double x0, final double y0, final double z0, final double x1, final double y1, final double z1) {
		return switch (facing) {
			case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
			case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
			case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
			default -> Block.box(x0, y0, z0, x1, y1, z1);
		};
	}
}
