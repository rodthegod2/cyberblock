package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Gates, garage doors and road barriers (section 1.11, toggle bit 2 {@code gate}): one panel of a multi-block sliding gate, a 4 px
 * slab standing across {@link #AXIS} (the axis the gate spans). {@link Gates} slides the panels in steps; a right-click on a panel
 * asks Night City to {@code open} the gate.
 */
public final class SlidingGateBlock extends DeviceBlock {
	public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
	private static final VoxelShape ALONG_X = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
	private static final VoxelShape ALONG_Z = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);

	SlidingGateBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(AXIS);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return state.getValue(AXIS) == Direction.Axis.X ? ALONG_X : ALONG_Z;
	}

	@Override
	protected BlockState rotate(final BlockState state, final Rotation rotation) {
		return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
			? state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X)
			: state;
	}

	@Override
	protected BlockState mirror(final BlockState state, final Mirror mirror) {
		return state;
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return DeviceHooks.act(state, level, pos, player, "open");
	}
}
