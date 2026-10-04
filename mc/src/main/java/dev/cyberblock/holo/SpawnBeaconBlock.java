package dev.cyberblock.holo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A Spawn Beacon (megaplan 7-T1): a placeable block that becomes a spawn point on the Holo-Table. Only in Night City (the
 * overworld), at most {@link Beacons#PER_PLAYER} per player; it remembers who placed it and, in a match, that player's team (its
 * team's spawn there). Breaking it (survival drops it again) removes the spawn point; explosions do not break it. A right-click
 * says whose it is.
 */
public final class SpawnBeaconBlock extends Block {
	SpawnBeaconBlock(final Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player && !HoloModule.mayPlaceBeacon(level, player)) {
			return null;
		}

		return super.getStateForPlacement(context);
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity placer, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player) {
			HoloModule.beaconPlaced(serverLevel, pos, player);
		}
	}

	@Override
	public BlockState playerWillDestroy(final Level level, final BlockPos pos, final BlockState state, final Player player) {
		if (level instanceof ServerLevel serverLevel) {
			HoloModule.beaconRemoved(serverLevel, pos, player);
			if (!player.isCreative()) {
				Block.popResource(level, pos, new ItemStack(this));
			}
		}

		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			HoloModule.beaconInfo(serverLevel, pos, serverPlayer);
			return InteractionResult.SUCCESS_SERVER;
		}

		return InteractionResult.SUCCESS;
	}
}
