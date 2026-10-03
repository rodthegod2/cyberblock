package dev.cyberblock.cyber;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** One quickhack (plan section 1.5): reusable, used on the target locked by holding the hack key. */
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
	@SuppressWarnings("deprecation")
	public void appendHoverText(final ItemStack itemStack, final Item.TooltipContext context, final TooltipDisplay display,
		final Consumer<Component> builder, final TooltipFlag tooltipFlag) {
		builder.accept(Component.translatable("hack.cyberblock." + this.hack.wire()).withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.cyberblock.quickhack.cost", this.hack.ram()).withStyle(ChatFormatting.GRAY));
	}
}
