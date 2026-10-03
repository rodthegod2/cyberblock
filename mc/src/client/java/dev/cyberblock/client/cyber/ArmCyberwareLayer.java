package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Steve's cyberware in third person (plan retest3 C6, C7): the Arms implant's 3D model on both arms (ArmCyberware), resolved
 * for the local player at the tail of AvatarRenderer.extractRenderState (AvatarRendererCyberMixin) and kept in the render
 * state under ARM.
 */
public final class ArmCyberwareLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** The local player's arm implant item (absent or empty: none). */
	public static final RenderStateDataKey<ItemStackRenderState> ARM = RenderStateDataKey.create(() -> "cyberblock:arm_cyberware");

	public ArmCyberwareLayer(final RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(final PoseStack poseStack, final SubmitNodeCollector collector, final int light, final AvatarRenderState state,
		final float yRot, final float xRot) {
		ItemStackRenderState arm = state.getData(ARM);
		if (arm == null || arm.isEmpty() || state.isInvisible) {
			return;
		}

		PlayerModel model = this.getParentModel();
		ArmCyberware.submit(arm, model.rightArm, HumanoidArm.RIGHT, poseStack, collector, light, state.outlineColor);
		ArmCyberware.submit(arm, model.leftArm, HumanoidArm.LEFT, poseStack, collector, light, state.outlineColor);
	}
}
