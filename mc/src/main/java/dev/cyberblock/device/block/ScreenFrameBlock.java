package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * TVs and billboards (section 1.11, toggle bit 512 {@code screen}): the screen's body (4A's art: a bezel with a lit or dark front,
 * {@link #ON}). Its glow item frames with the screen's map picture follow the blocks by themselves, however the server placed them
 * (4M sets the state directly): a new body block, or one switched on or off, refreshes its whole panel a moment later
 * ({@link ScreenFrames#refresh}), and a frame whose body is gone is taken away ({@link ScreenFrames#init}). Breaching a screen for a
 * distraction is Phase 6, so a right-click does nothing yet.
 */
public final class ScreenFrameBlock extends FacingDeviceBlock {
	public static final BooleanProperty ON = BooleanProperty.create("on");
	/** Ticks between a body change and its panel's refresh (the rest of a panel the server sets in the same tick is in by then). */
	static final int REFRESH_TICKS = 2;

	ScreenFrameBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ON, true).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(ON);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return InteractionResult.PASS;
	}

	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState old, final boolean movedByPiston) {
		super.onPlace(state, level, pos, old, movedByPiston);
		if (!level.isClientSide() && (!old.is(this) || old.getValue(ON) != state.getValue(ON) || old.getValue(FACING) != state.getValue(FACING))) {
			level.scheduleTick(pos, this, REFRESH_TICKS);
		}
	}

	@Override
	protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		ScreenFrames.refresh(level, pos, state.getValue(FACING));
	}

	static int light(final BlockState state) {
		return state.getValue(ON) ? 8 : 0;
	}
}
