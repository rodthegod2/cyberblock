// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.skin.CyberSkin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Megaplan 5-K1: every player the roster flags as linked wears the cyber skin, on every client and in the local player's own view
 * (the third-person body, the first-person arms and the model choice all read getSkin). It replaces the Phase Q classic-Steve rule;
 * {@code -Dcyberblock.ownSkin} is ignored (SkinClient logs it).
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void cyberblock$cyberSkin(final CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = CyberSkin.of(((AbstractClientPlayer) (Object) this).getUUID());
		if (skin != null) {
			cir.setReturnValue(skin);
		}
	}
}
