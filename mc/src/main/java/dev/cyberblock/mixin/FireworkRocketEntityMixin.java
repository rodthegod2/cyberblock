package dev.cyberblock.mixin;

import dev.cyberblock.ExplosionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A rocket bursting at the end of its flight or on an impact; one that carries no stars (an elytra boost) only fizzles. */
@Mixin(FireworkRocketEntity.class)
abstract class FireworkRocketEntityMixin {
	@Shadow
	protected abstract boolean hasExplosion();

	@Inject(method = "explode", at = @At("HEAD"))
	private void cyberblock$burst(final ServerLevel level, final CallbackInfo ci) {
		if (this.hasExplosion()) {
			FireworkRocketEntity rocket = (FireworkRocketEntity) (Object) this;
			ExplosionEvents.reportFirework(level, rocket.getX(), rocket.getY(), rocket.getZ());
		}
	}
}
