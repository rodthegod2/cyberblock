package dev.cyberblock.cyber;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** A RAM shard: used, it adds 4 RAM up to the maximum (task C2). Needs a cyberdeck, and is kept when RAM is already full. */
public class RamShardItem extends Item {
	public RamShardItem(final Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		// both sides decide alike (RAM, perks and inventory are synced); only the server changes anything
		int max = CyberData.maxRam(player);
		int ram = CyberData.ram(player);
		if (max == 0 || ram >= max) {
			if (!level.isClientSide()) {
				player.sendOverlayMessage(Component.translatable(max == 0 ? "cyberblock.hack.no_deck" : "cyberblock.ram.full"));
			}

			return InteractionResult.FAIL;
		}

		if (!level.isClientSide()) {
			CyberData.setRam(player, Ram.shard(ram, max));
			player.getItemInHand(hand).consume(1, player);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
		}

		return InteractionResult.SUCCESS;
	}
}
