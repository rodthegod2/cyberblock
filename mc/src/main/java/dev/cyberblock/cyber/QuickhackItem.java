package dev.cyberblock.cyber;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * One quickhack (plan section 1.5): reusable, used on the target locked by holding the hack key. The server decides (it
 * holds the lock): RAM, cooldown and the `hack` message, or an action-bar message saying what is missing.
 */
public class QuickhackItem extends Item {
	private final Hack hack;

	public QuickhackItem(final Hack hack, final Item.Properties properties) {
		super(properties);
		this.hack = hack;
	}

	public Hack hack() {
		return this.hack;
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// the client cannot know the lock: it waits for the server's verdict and lets the click end here (no off-hand use)
			return InteractionResult.CONSUME;
		}

		return Hacking.use(serverPlayer, player.getItemInHand(hand), this.hack) ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(final ItemStack itemStack, final Item.TooltipContext context, final TooltipDisplay display,
		final Consumer<Component> builder, final TooltipFlag tooltipFlag) {
		builder.accept(Component.translatable("hack.cyberblock." + this.hack.wire()).withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.cyberblock.quickhack.cost", this.hack.ram()).withStyle(ChatFormatting.GRAY));
	}
}
