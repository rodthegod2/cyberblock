package dev.cyberblock.hub;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** A leaderboard board (megaplan 7-H1): its face shows the top of a scoreboard objective; a right-click lists the top ten in chat. */
public final class LeaderboardBlock extends HubBlocks.Facing implements EntityBlock {
	LeaderboardBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new LeaderboardBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		return level.isClientSide() || type != HubBlocks.LEADERBOARD_ENTITY ? null
			: (l, pos, s, be) -> LeaderboardBlockEntity.tick(l, pos, s, (LeaderboardBlockEntity) be);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof LeaderboardBlockEntity board) {
			ServerScoreboard scores = serverPlayer.level().getServer().getScoreboard();
			var objective = scores.getObjective(board.objective());
			List<Leaderboards.Row> rows = objective == null ? List.of() : Leaderboards.top(scores.listPlayerScores(objective), Leaderboards.LISTED);
			serverPlayer.sendSystemMessage(Leaderboards.title(board.objective(), board.titleKey()).copy().withStyle(ChatFormatting.GOLD));
			if (rows.isEmpty()) {
				serverPlayer.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.board.empty", "No scores yet"));
			}

			for (int i = 0; i < rows.size(); i++) {
				serverPlayer.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.board.row", "%s. %s: %s", i + 1, rows.get(i).name(), rows.get(i).value()));
			}
		}

		return InteractionResult.SUCCESS;
	}
}
