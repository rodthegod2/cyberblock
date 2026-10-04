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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The elevator call button: a small panel on the wall beside the shaft ({@link #FACING} = the way it faces, as a vanilla button), lit
 * ({@link #LIT}) while a call is on its way. Phase 0 c found no proven way to call a Night City lift ({@link #CALL_SUPPORTED} false):
 * the button stays dark (greyed) and a press says {@code cyberblock.device.unsupported}; once a check proves it, a press asks for
 * {@code call}.
 */
public final class LiftCallButtonBlock extends FacingDeviceBlock {
	/** Megaplan 1.9 amendment item 6: lift calls are unproven (Phase 0 c). Flip it when an in-game check proves {@code call}. */
	public static final boolean CALL_SUPPORTED = false;
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	private static final VoxelShape NORTH = Block.box(4.0, 3.0, 14.0, 12.0, 13.0, 16.0);
	private static final VoxelShape SOUTH = Block.box(4.0, 3.0, 0.0, 12.0, 13.0, 2.0);
	private static final VoxelShape WEST = Block.box(14.0, 3.0, 4.0, 16.0, 13.0, 12.0);
	private static final VoxelShape EAST = Block.box(0.0, 3.0, 4.0, 2.0, 13.0, 12.0);

	LiftCallButtonBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(LIT);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return switch (state.getValue(FACING)) {
			case SOUTH -> SOUTH;
			case WEST -> WEST;
			case EAST -> EAST;
			default -> NORTH;
		};
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (!CALL_SUPPORTED) {
			return DeviceHooks.say(player, "cyberblock.device.unsupported", "Night City does not let Minecraft do this yet");
		}

		return DeviceHooks.act(state, level, pos, player, "call");
	}

	/** A call button facing {@code facing}, dark. */
	public static BlockState state(final Direction facing) {
		return DeviceBlocks.LIFT_CALL_BUTTON.defaultBlockState().setValue(FACING, facing).setValue(LIT, false);
	}

	static int light(final BlockState state) {
		return state.getValue(LIT) ? 6 : 0;
	}
}
