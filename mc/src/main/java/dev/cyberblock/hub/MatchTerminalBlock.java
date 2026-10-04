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
 * The Match Terminal (megaplan 7-H1): a right-click opens Phase 8's match screen through {@link #opener}, which Phase 8 (package 8M)
 * sets; until then it says that matches are coming in Phase 8.
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
				serverPlayer.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.match_terminal.soon",
					"Match Terminal: matches are coming in Phase 8"), true);
			}
		}

		return InteractionResult.SUCCESS;
	}
}
