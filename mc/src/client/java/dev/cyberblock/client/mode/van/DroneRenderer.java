package dev.cyberblock.client.mode.van;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.mode.van.VanDrone;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * A Van's drone (megaplan 8-V1): a small quadcopter of boxes (a body, crossed arms, four spinning rotors) in the player's own vanilla
 * concrete, with an eye that glows green, yellow or red with its battery. Original geometry; no art file needed.
 */
final class DroneRenderer extends EntityRenderer<VanDrone, DroneRenderer.State> {
	private static final Identifier BODY = Identifier.withDefaultNamespace("textures/block/black_concrete.png");
	private static final Identifier LIGHT = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
	private static final float SPIN = 1.7F;
	private final Model<State> body, eye;
	private final RenderType bodyType = RenderTypes.entityCutout(BODY), eyeType = RenderTypes.entityCutout(LIGHT);

	static final class State extends EntityRenderState {
		float yRot;
		float battery;
	}

	DroneRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.body = body();
		this.eye = eye();
		this.shadowRadius = 0.3F;
	}

	private static Model<State> body() {
		MeshDefinition mesh = new MeshDefinition();
		mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create().addBox(-3.0F, -4.0F, -3.0F, 6, 2, 6), PartPose.ZERO);
		mesh.getRoot().addOrReplaceChild("arms", CubeListBuilder.create().addBox(-7.0F, -4.0F, -0.5F, 14, 1, 1).addBox(-0.5F, -4.0F, -7.0F, 1, 1, 14),
			PartPose.ZERO);
		for (int i = 0; i < 4; i++) {
			float x = i % 2 == 0 ? -6.5F : 6.5F, z = i < 2 ? -6.5F : 6.5F;
			mesh.getRoot().addOrReplaceChild("rotor" + i, CubeListBuilder.create().addBox(-2.5F, -0.5F, -0.4F, 5, 1, 0.8F), PartPose.offset(x, -5.0F, z));
		}

		ModelPart root = LayerDefinition.create(mesh, 16, 16).bakeRoot();
		ModelPart[] rotors = {root.getChild("rotor0"), root.getChild("rotor1"), root.getChild("rotor2"), root.getChild("rotor3")};
		return new Model<>(root, RenderTypes::entityCutout) {
			@Override
			public void setupAnim(final State state) {
				super.setupAnim(state);
				for (int i = 0; i < rotors.length; i++) {
					rotors[i].yRot = state.ageInTicks * SPIN * (i % 2 == 0 ? 1 : -1);
				}
			}
		};
	}

	private static Model<State> eye() {
		MeshDefinition mesh = new MeshDefinition();
		// at -z: the half-turn about x below puts it at the front, the way the drone looks
		mesh.getRoot().addOrReplaceChild("eye", CubeListBuilder.create().addBox(-1.0F, -3.5F, -3.6F, 2, 1, 1), PartPose.ZERO);
		return new Model<>(LayerDefinition.create(mesh, 16, 16).bakeRoot(), RenderTypes::entityCutout) {
		};
	}

	/** The eye's colour (ARGB) for a battery share: green, then yellow under half, red under a fifth. */
	static int eyeColour(final float battery) {
		return battery < 0.2F ? 0xFFFF3030 : battery < 0.5F ? 0xFFFFD030 : 0xFF40FF60;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final VanDrone entity, final State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yRot = Mth.rotLerp(partialTicks, entity.yRotO, entity.getYRot());
		state.battery = entity.battery();
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.yRot);
		poseStack.rotateDegrees(Axis.XP, 180.0F);
		collector.submitModel(this.body, state, poseStack, this.bodyType, state.lightCoords, OverlayTexture.NO_OVERLAY, 0xFF3A3F48, null, state.outlineColor);
		collector.submitModel(this.eye, state, poseStack, this.eyeType, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, eyeColour(state.battery), null,
			state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
