package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The red lamp above a locked device door (section 1.11); grey and dark when Night City holds the door for a quest
 * ({@link #QUEST_LOCKED}). {@link Doors} places and removes it with the lock.
 */
public final class DoorLockLampBlock extends DeviceBlock {
	DoorLockLampBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(QUEST_LOCKED, false));
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return InteractionResult.PASS;
	}

	static int light(final BlockState state) {
		return state.getValue(QUEST_LOCKED) ? 0 : 7;
	}
}
