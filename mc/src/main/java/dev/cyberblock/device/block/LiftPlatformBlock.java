package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Elevators (section 1.11, toggle bit 128 {@code elevator}): the floor of the car, a 4 px plate (4A's art). {@link Lifts} moves the
 * platform layer up and down its shaft to the floor Night City reports, carrying whoever stands on it, {@link #MOVING} while it goes;
 * the call button is {@link LiftCallButtonBlock}.
 */
public final class LiftPlatformBlock extends DeviceBlock {
	public static final BooleanProperty MOVING = BooleanProperty.create("moving");
	/** The plate's height in blocks: riders stand this high above the platform's cell. */
	public static final double HEIGHT = 0.25;
	private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0);

	LiftPlatformBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(MOVING, false).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(MOVING);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return InteractionResult.PASS;
	}
}
