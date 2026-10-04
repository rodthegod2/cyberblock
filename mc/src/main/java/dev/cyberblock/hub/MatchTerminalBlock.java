package dev.cyberblock.hub;

import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Match Terminal (megaplan 7-H1): a right-click opens the match screen through {@link #opener}, which package 8M sets
 * ({@code dev.cyberblock.mode.match.Matches}: the screen, and creating a match needs a terminal within 8 blocks); without it the
 * terminal says that matches are coming.
 */
public final class MatchTerminalBlock extends HubBlocks.Facing {
	/** Phase 8M: opens the match screen for the player at this terminal. Null until Phase 8. Server thread. */
	public static volatile @Nullable BiConsumer<ServerPlayer, BlockPos> opener;

	MatchTerminalBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			BiConsumer<ServerPlayer, BlockPos> open = opener;
			if (open != null) {
				open.accept(serverPlayer, pos);
			} else {
				serverPlayer.sendSystemMessage(Component.translatableWithFallback("cyberblock.afterlife.terminal.soon",
					"The Match Terminal is not connected yet: matches are coming soon"), true);
			}
		}

		return InteractionResult.SUCCESS;
	}
}
