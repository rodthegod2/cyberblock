package dev.cyberblock.holo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Holo-Table (megaplan 7-T1): a block in the Afterlife hub (7H places it from the hub layout; operators can place more). A
 * right-click opens the table view on the client: the server grants the player the table and sends its first {@code holo} state
 * with {@code open:true}. Unbreakable in survival like the hub's other furniture.
 */
public final class HoloTableBlock extends Block {
	HoloTableBlock(final Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			HoloModule.open(serverPlayer, pos);
			return InteractionResult.SUCCESS_SERVER;
		}

		return InteractionResult.SUCCESS;
	}
}
