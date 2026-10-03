package dev.cyberblock.cyber.mixin;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Field medic (plan section 1.5): instant health heals half as much again (potions, splash and lingering, arrows). */
@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
abstract class HealOrHarmMobEffectMixin {
	@Redirect(
		method = {"applyEffectTick", "applyInstantaneousEffect"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;heal(F)V")
	)
	private void cyberblock$fieldMedic(final LivingEntity mob, final float amount) {
		mob.heal(mob instanceof ServerPlayer player && CyberData.has(player, Perk.FIELD_MEDIC) ? PerkRules.heal(amount) : amount);
	}
}
