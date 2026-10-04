package dev.cyberblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cyberblock.ExplosionEvents;
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

/**
 * Every explosion of the server world (TNT, creepers, beds, anchors, ghast fireballs, wind charges, commands) funnels through explode:
 * the radius gets the demolitions perk first (blasts only: a wind burst keeps its size), then the hosts near it hear of it with the
 * radius it really has. An explosion another package owns (ExplosionEvents.owner: the Overlord's airstrikes and mobs) runs with that
 * package's interaction and damage calculator, and only the hosts it names hear of it.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapMethod(method = "explode")
	private void cyberblock$exploded(final @Nullable Entity source, final @Nullable DamageSource damageSource, final @Nullable ExplosionDamageCalculator damageCalculator,
		final double x, final double y, final double z, final float r, final boolean fire, final Level.ExplosionInteraction interactionType,
		final ParticleOptions smallExplosionParticles, final ParticleOptions largeExplosionParticles, final WeightedList<ExplosionParticleInfo> blockParticles,
		final Holder<SoundEvent> explosionSound, final Operation<Void> original) {
		ExplosionEvents.Owned owned = ExplosionEvents.owner.apply(source, damageCalculator);
		if (owned != null) {
			ExplosionEvents.send((ServerLevel) (Object) this, owned.interaction(), x, y, z, r, fire, owned.hears());
			original.call(source, damageSource, owned.damage(), x, y, z, r, fire, owned.interaction(), smallExplosionParticles, largeExplosionParticles,
				blockParticles, explosionSound);
			return;
		}

		float radius = ExplosionEvents.radius(interactionType, source, damageSource, r);
		ExplosionEvents.report((ServerLevel) (Object) this, interactionType, x, y, z, radius, fire, ExplosionEvents.cause(source, damageSource));
		original.call(source, damageSource, damageCalculator, x, y, z, radius, fire, interactionType, smallExplosionParticles, largeExplosionParticles,
			blockParticles, explosionSound);
	}
}
