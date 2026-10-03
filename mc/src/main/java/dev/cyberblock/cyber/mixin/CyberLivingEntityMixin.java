package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareEffects;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Silent steps (plan section 1.5) or lynx paws (plan retest2 section 1.7): mobs target the player from half the range, once
 * whichever he has. Their targeting conditions scale the follow range by this visibility factor (as sneaking does, x0.8).
 * Second Heart: when no totem saved him (LivingEntity.checkTotemDeathProtection, 26.3), the implant does.
 */
@Mixin(LivingEntity.class)
abstract class CyberLivingEntityMixin {
	@ModifyReturnValue(method = "getVisibilityPercent", at = @At("RETURN"))
	private double cyberblock$silentSteps(final double percent) {
		return (Object) this instanceof ServerPlayer player
			&& (CyberData.has(player, Perk.SILENT_STEPS) || CyberData.has(player, Cyberware.LYNX_PAWS)) ? PerkRules.visibility(percent) : percent;
	}

	@Inject(method = "checkTotemDeathProtection", at = @At("RETURN"), cancellable = true, require = 1)
	private void cyberblock$secondHeart(final DamageSource killingDamage, final CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && (Object) this instanceof ServerPlayer player && CyberwareEffects.secondHeart(player, killingDamage)) {
			cir.setReturnValue(true);
		}
	}
}
