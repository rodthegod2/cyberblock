// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.PlayerSync;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local player is placed every frame, so its own tick movement is ~0: animate the walk from the host's movement. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "updateWalkAnimation", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostWalk(final float distance, final CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player && HostState.frame() != null) {
			player.walkAnimation.update(Math.min(PlayerSync.tickDistance() * 4.0F, 1.0F), 0.4F, 1.0F);
			ci.cancel();
		}
	}
}
