package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Quick draw (plan section 1.5): a crossbow loads in 0.7 of the time (both sides read the synced perk, so the animation agrees). */
@Mixin(CrossbowItem.class)
abstract class CrossbowItemMixin {
	@ModifyReturnValue(method = "getChargeDuration", at = @At("RETURN"))
	private static int cyberblock$quickDraw(final int ticks, @Local(argsOnly = true) final LivingEntity user) {
		return user instanceof Player player && CyberData.has(player, Perk.QUICK_DRAW) ? PerkRules.crossbowCharge(ticks) : ticks;
	}
}
