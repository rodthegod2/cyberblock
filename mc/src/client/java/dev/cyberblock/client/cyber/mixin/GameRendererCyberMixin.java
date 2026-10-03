package dev.cyberblock.client.cyber.mixin;

import dev.cyberblock.client.cyber.SandevistanFx;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plan retest3 C7: GameRenderer.update (26.3) rebuilds the requested post effects every frame (end of frame, the player's,
 * the spectated entity's); at its tail the Sandevistan's own one is added while it runs in a standalone Minecraft.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererCyberMixin {
	@Shadow
	@Final
	private List<Identifier> requestedPostEffects;

	@Inject(method = "update", at = @At("TAIL"))
	private void cyberblock$sandevistanPost(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		if (SandevistanFx.wantsPostEffect()) {
			this.requestedPostEffects.add(SandevistanFx.POST_EFFECT);
		}
	}
}
