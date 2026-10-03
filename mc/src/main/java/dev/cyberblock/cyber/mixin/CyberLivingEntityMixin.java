package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Silent steps (plan section 1.5): mobs target a player with the perk from half the range. Their targeting conditions
 * scale the follow range by this visibility factor (as sneaking does, x0.8).
 */
@Mixin(LivingEntity.class)
abstract class CyberLivingEntityMixin {
	@ModifyReturnValue(method = "getVisibilityPercent", at = @At("RETURN"))
	private double cyberblock$silentSteps(final double percent) {
		return (Object) this instanceof ServerPlayer player && CyberData.has(player, Perk.SILENT_STEPS) ? PerkRules.visibility(percent) : percent;
	}
}
