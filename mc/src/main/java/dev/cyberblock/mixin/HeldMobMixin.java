package dev.cyberblock.mixin;

import dev.cyberblock.MobWar;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fighter held off unknown ground (MobWar): immobile (no AI step, no walking, no jumping) and without gravity, with nothing
 * written into the mob, so nothing of the hold is ever saved.
 */
@Mixin(LivingEntity.class)
abstract class HeldMobMixin {
	@Inject(method = "isImmobile()Z", at = @At("HEAD"), cancellable = true)
	private void cyberblock$held(final CallbackInfoReturnable<Boolean> cir) {
		if (MobWar.isHeld((LivingEntity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getEffectiveGravity()D", at = @At("HEAD"), cancellable = true)
	private void cyberblock$heldUp(final CallbackInfoReturnable<Double> cir) {
		if (MobWar.isHeld((LivingEntity) (Object) this)) {
			cir.setReturnValue(0.0);
		}
	}
}
