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
 * One implant (plan retest2 section 1.7): used (right-click) it is installed by the ripperdoc on the server, as a click in
 * the cyberware screen would. The tooltip names its slot, what it does in both games, its cooldown and how to install it.
 */
public class CyberwareItem extends Item {
	private final Cyberware cyberware;

	public CyberwareItem(final Cyberware cyberware, final Item.Properties properties) {
		super(properties);
		this.cyberware = cyberware;
	}

	public Cyberware cyberware() {
		return this.cyberware;
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			// the server decides (slots, creative, what comes back): the click ends here, as for a quickhack
			return InteractionResult.CONSUME;
		}

		Ripperdoc.install(serverPlayer, this.cyberware);
		return InteractionResult.SUCCESS_SERVER;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(final ItemStack itemStack, final Item.TooltipContext context, final TooltipDisplay display,
		final Consumer<Component> builder, final TooltipFlag tooltipFlag) {
		String key = "cyberware.cyberblock." + this.cyberware.id();
		builder.accept(Component.translatable("cyberblock.cyberware.tooltip.slot", Component.translatable(this.cyberware.slot().langKey())).withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("cyberblock.skills.minecraft", Component.translatable(key + ".mc")).withStyle(ChatFormatting.WHITE));
		builder.accept(Component.translatable("cyberblock.skills.cyberpunk", Component.translatable(key + ".cp")).withStyle(ChatFormatting.LIGHT_PURPLE));
		if (this.cyberware.cooldownTicks() > 0) {
			builder.accept(Component.translatable("cyberblock.cyberware.tooltip.cooldown", this.cyberware.cooldownTicks() / 20).withStyle(ChatFormatting.GRAY));
		}

		builder.accept(Component.translatable("cyberblock.cyberware.tooltip.install", Component.keybind("key.cyberblock.cyberware")).withStyle(ChatFormatting.DARK_GRAY));
	}
}
