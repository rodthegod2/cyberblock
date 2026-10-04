package dev.cyberblock.mode.heat.mixin;

import dev.cyberblock.ExplosionEvents;
import dev.cyberblock.mode.heat.ServerHeat;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every explosion of the server world funnels through ServerLevel.explode (the world track's ServerLevelMixin wraps the same method):
 * a blast in the overworld feeds Server Heat, credited to the player behind it (megaplan 8-T1).
 */
@Mixin(ServerLevel.class)
abstract class HeatExplosionMixin {
	@Inject(method = "explode", at = @At("HEAD"))
	private void cyberblock$heat(final @Nullable Entity source, final @Nullable DamageSource damageSource, final @Nullable ExplosionDamageCalculator damageCalculator,
		final double x, final double y, final double z, final float r, final boolean fire, final Level.ExplosionInteraction interactionType,
		final ParticleOptions smallExplosionParticles, final ParticleOptions largeExplosionParticles, final WeightedList<ExplosionParticleInfo> blockParticles,
		final Holder<SoundEvent> explosionSound, final CallbackInfo ci) {
		ServerHeat.explosion((ServerLevel) (Object) this, interactionType, x, y, z, r, ExplosionEvents.cause(source, damageSource));
	}
}
