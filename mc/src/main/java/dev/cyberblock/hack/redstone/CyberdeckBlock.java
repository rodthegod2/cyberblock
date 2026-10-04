package dev.cyberblock.hack.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Cyberdeck block (megaplan 6-R1): RAM for the Breach Ports next to it. It holds {@link RedstoneRules#DECK_MAX_RAM} and gets one
 * point back every 5 s; a comparator reads how full it is; a right-click says so.
 */
public final class CyberdeckBlock extends HackBlock {
	CyberdeckBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected boolean hasAnalogOutputSignal(final BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(final BlockState state, final Level level, final BlockPos pos, final Direction direction) {
		return level.getBlockEntity(pos) instanceof HackBlockEntity be ? be.deck(RedstoneHacks.now(level)).signal(RedstoneHacks.now(level)) : 0;
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HackBlockEntity be) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.redstone.deck_ram", "Cyberdeck block: %s / %s RAM (1 back every 5 s)",
				be.deck(RedstoneHacks.now(level)).ram(RedstoneHacks.now(level)), RedstoneRules.DECK_MAX_RAM));
		}

		return InteractionResult.SUCCESS;
	}
}
