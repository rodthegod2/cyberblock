package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Elevators (section 1.11, toggle bit 128 {@code elevator}): the floor of the car. {@link Lifts} moves the platform layer up and down
 * its shaft to the floor Night City reports, carrying whoever stands on it; the call button is {@link LiftCallButtonBlock}.
 */
public final class LiftPlatformBlock extends DeviceBlock {
	LiftPlatformBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(QUEST_LOCKED, false));
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return InteractionResult.PASS;
	}
}
