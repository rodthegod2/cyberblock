// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every fire puffs big black smoke squares, which over the host's picture is mostly clutter: none while attached. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
	private void cyberblock$noFireSmoke(final ParticleOptions particle, final double x, final double y, final double z,
		final double xd, final double yd, final double zd, final CallbackInfo ci) {
		if (HostState.active() && particle.getType() == ParticleTypes.LARGE_SMOKE) {
			ci.cancel();
		}
	}
}
