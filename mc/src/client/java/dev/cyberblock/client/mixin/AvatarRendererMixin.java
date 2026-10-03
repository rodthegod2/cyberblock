// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holding one of the host's guns (drawn by the host): Steve aims with both arms forward, and holds nothing of his own. In a vehicle he
 * takes vanilla's riding pose (HumanoidRenderState.isPassenger: legs forward), sitting where V sits (plan retest3 M2, F3).
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cyberblock$hostPose(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player) {
			return;
		}

		if (HostState.vehicle()) {
			state.isPassenger = true;
			state.isCrouching = false;
		}

		HostState.Pose p = HostState.frame();
		if (p == null || !p.gun()) {
			return;
		}

		state.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
		state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
		state.rightHandItemState.clear();
		state.leftHandItemState.clear();
		state.rightHandItemStack = ItemStack.EMPTY;
		state.leftHandItemStack = ItemStack.EMPTY;
	}
}
