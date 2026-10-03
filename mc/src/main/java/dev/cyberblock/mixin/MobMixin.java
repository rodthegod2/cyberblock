// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft is held at noon while Cyberpunk drives (so its lighting stays bright): the undead do not burn in that sun. */
@Mixin(Mob.class)
abstract class MobMixin {
	@Inject(method = "burnUndead()V", at = @At("HEAD"), cancellable = true)
	private void cyberblock$noSunburn(final CallbackInfo ci) {
		if (Cyberblock.active) {
			ci.cancel();
		}
	}
}
