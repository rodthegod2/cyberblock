package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * {@link #ON}). {@link ScreenFrames} hangs a glow item frame with the screen's map picture on its front. Breaching a screen for a
 * distraction is Phase 6, so a right-click does nothing yet.
 */
public final class ScreenFrameBlock extends FacingDeviceBlock {
	public static final BooleanProperty ON = BooleanProperty.create("on");

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

	static int light(final BlockState state) {
		return state.getValue(ON) ? 8 : 0;
	}
}
