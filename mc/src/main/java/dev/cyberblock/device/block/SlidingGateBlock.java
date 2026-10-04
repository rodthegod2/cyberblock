package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
