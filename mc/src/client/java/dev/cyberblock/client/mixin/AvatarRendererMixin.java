// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.PlayerSync;
import dev.cyberblock.client.view.ViewPoses;
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
 * takes vanilla's riding pose (HumanoidRenderState.isPassenger: legs forward), sitting where V sits (plan retest3 M2, F3). In third
 * person he is drawn at the feet of this frame's pose (Q-V1) and without pose flicker (Q-V2, ViewPoses).
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cyberblock$hostPose(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player) {
			return;
		}

		HostState.Pose p = HostState.frame();
		if (p == null) {
			ViewPoses.reset(state);
			return;
		}

		if (HostState.vehicle()) {
			state.isPassenger = true;
			state.isCrouching = false;
		}

		// third person, on foot, seated, gliding or launched: at the feet of the cam this frame is rendered and exported with, where
		// the camera that frames him follows, not where the tick or Minecraft's physics have him (gliding: up to a few blocks ahead,
		// in-game 2026-10-03). The compositor then moves him with V (Cyberblock.fx cb_body_*).
		double[] feet = p.firstPerson() ? null : PlayerSync.renderFeet();
		if (feet != null) {
			state.x = feet[0];
			state.y = feet[1];
			state.z = feet[2];
		}

		ViewPoses.apply(entity, state);
		if (!p.gun()) {
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
