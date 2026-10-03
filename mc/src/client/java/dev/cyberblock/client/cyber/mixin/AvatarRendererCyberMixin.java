package dev.cyberblock.client.cyber.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cyberblock.client.cyber.ArmCyberware;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plan retest3 C6/C7, on AvatarRenderer (26.3): the local player's cyberware goes into the render state at the tail of
 * extractRenderState (ArmCyberwareLayer draws it in third person), and the arm implant onto the first-person hand at the
 * tail of renderHand(PoseStack, SubmitNodeCollector, int, Identifier, ModelPart, boolean).
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererCyberMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cyberblock$extractCyberware(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		ArmCyberware.extract(entity, state, partialTick);
	}

	@Inject(method = "renderHand", at = @At("TAIL"))
	private void cyberblock$handCyberware(final PoseStack poseStack, final SubmitNodeCollector collector, final int light, final Identifier skin,
		final ModelPart arm, final boolean hasSleeve, final CallbackInfo ci) {
		ArmCyberware.hand((AvatarRenderer<?>) (Object) this, poseStack, collector, light, arm);
	}
}
