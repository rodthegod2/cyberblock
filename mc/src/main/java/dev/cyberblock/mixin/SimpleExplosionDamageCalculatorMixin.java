package dev.cyberblock.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cyberblock.Cyberblock;
import net.minecraft.world.level.SimpleExplosionDamageCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Plan retest2 R5, with ServerExplosionMixin: wind charges (WindCharge, BreezeWindCharge, AbstractWindCharge) explode with a
 * SimpleExplosionDamageCalculator, whose getKnockbackMultiplier (Minecraft 26.3) is 0 for a player whose abilities say flying. A
 * creative Steve only "flies" because PlayerSync hangs him at V, so while Cyberblock is active he is knocked back like a player on
 * foot and his own wind charge can launch him.
 */
@Mixin(SimpleExplosionDamageCalculator.class)
abstract class SimpleExplosionDamageCalculatorMixin {
	@ModifyExpressionValue(method = "getKnockbackMultiplier", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z"))
	private boolean cyberblock$creativeSteveIsKnockedBack(final boolean flying) {
		return flying && !Cyberblock.active;
	}
}
