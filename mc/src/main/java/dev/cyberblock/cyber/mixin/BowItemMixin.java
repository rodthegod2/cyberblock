package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Quick draw (plan section 1.5): a bow reaches full power 30 % sooner (the time held counts 1/0.7 as much). */
@Mixin(BowItem.class)
abstract class BowItemMixin {
	@ModifyArg(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BowItem;getPowerForTime(I)F"))
	private int cyberblock$quickDraw(final int timeHeld, @Local(argsOnly = true) final LivingEntity entity) {
		return entity instanceof Player player && CyberData.has(player, Perk.QUICK_DRAW) ? PerkRules.bowTimeHeld(timeHeld) : timeHeld;
	}
}
