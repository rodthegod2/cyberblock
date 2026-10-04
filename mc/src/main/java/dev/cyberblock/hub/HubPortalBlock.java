package dev.cyberblock.hub;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The hub portal: a right-click takes the player to the Afterlife, or, inside it, back to where they came from (megaplan 7-H1). */
public final class HubPortalBlock extends Block {
	HubPortalBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			HubTravel.portal(serverPlayer);
		}

		return InteractionResult.SUCCESS;
	}
}
