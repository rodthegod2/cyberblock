package dev.cyberblock.client.mode.van;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.figure.VehicleModels;
import dev.cyberblock.figure.VehicleModel;
import dev.cyberblock.mode.van.VanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The Netrunner Van (megaplan 8-V1, "Phase 5 vehicle model"): Phase 5's van boxes and textures (VehicleModels, 5A's art) painted a
 * netrunner's dark violet, with a roof dish and an antenna of the same paint. Without 5A's textures the boxes are tinted vanilla
 * white concrete. Its own netrunner, looking out in first person from the seat inside, does not see the body around him.
 */
final class VanRenderer extends EntityRenderer<VanEntity, VanRenderer.State> {
	static final int PAINT = 0x3B2D5C;
	private static final Identifier PLAIN = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
	private static final Identifier BODY = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/vehicle/van.png");
	private static final Identifier DETAIL = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/vehicle/van_detail.png");
	private static final float DETAIL_GROW = 0.05F;
	private final Model<State> paint, detail;
	private final RenderType paintType;
	private final @Nullable RenderType detailType;

	static final class State extends EntityRenderState {
		float yRot;
		boolean hiddenForMe;
	}

	VanRenderer(final EntityRendererProvider.Context context) {
		super(context);
		boolean textured = context.getResourceManager().getResource(BODY).isPresent() && context.getResourceManager().getResource(DETAIL).isPresent();
		this.paint = model(0.0F, true);
		this.detail = model(DETAIL_GROW, false);
		this.paintType = RenderTypes.entityCutout(textured ? BODY : PLAIN);
		this.detailType = textured ? RenderTypes.entityCutout(DETAIL) : null;
		this.shadowRadius = 1.3F;
		Cyberblock.LOG.info(textured ? "van: the Van wears the van textures (textures/entity/vehicle/van*.png)" : "van: van textures missing, a plain painted Van");
	}

	/** The van's boxes (half scale, as VehicleModels), plus, on the paint layer, the roof gear. */
	private static Model<State> model(final float grow, final boolean gear) {
		MeshDefinition mesh = new MeshDefinition();
		for (VehicleModels.Part p : VehicleModels.parts(VehicleModel.VAN)) {
			CubeListBuilder boxes = CubeListBuilder.create().texOffs(p.u(), p.v());
			for (int[] at : p.at()) {
				boxes.addBox(at[0], at[1], at[2], p.w(), p.h(), p.d(), new CubeDeformation(grow));
			}

			mesh.getRoot().addOrReplaceChild(p.name(), boxes, PartPose.ZERO);
		}

		if (gear) {
			// a dish on the roof and a mast behind it, in the body's paint (model px, y down: the roof is at -16)
			mesh.getRoot().addOrReplaceChild("dish", CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -17.0F, 2.0F, 6, 1, 6), PartPose.ZERO);
			mesh.getRoot().addOrReplaceChild("mast", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5F, -23.0F, 12.0F, 1, 7, 1), PartPose.ZERO);
		}

		ModelPart root = LayerDefinition.create(mesh, VehicleModels.TEXTURE, VehicleModels.TEXTURE).bakeRoot();
		return new Model<>(root, RenderTypes::entityCutout) {
		};
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final VanEntity entity, final State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yRot = Mth.rotLerp(partialTicks, entity.yRotO, entity.getYRot());
		Minecraft minecraft = Minecraft.getInstance();
		state.hiddenForMe = minecraft.player != null && minecraft.player.getVehicle() == entity && minecraft.getCameraEntity() == minecraft.player
			&& minecraft.options.getCameraType().isFirstPerson();
	}

	@Override
	protected AABB getBoundingBoxForCulling(final VanEntity entity, final float partialTicks) {
		return super.getBoundingBoxForCulling(entity, partialTicks).inflate(1.5, 1.0, 1.5);
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		if (!state.hiddenForMe) {
			poseStack.pushPose();
			// as VehicleFigureRenderer: the model's +z is its front; the boxes have y down at half scale
			poseStack.rotateDegrees(Axis.YP, -state.yRot);
			poseStack.rotateDegrees(Axis.XP, 180.0F);
			poseStack.scale(VehicleModels.SCALE, VehicleModels.SCALE, VehicleModels.SCALE);
			collector.submitModel(this.paint, state, poseStack, this.paintType, state.lightCoords, OverlayTexture.NO_OVERLAY, VehicleModels.paint(PAINT, false),
				null, state.outlineColor);
			if (this.detailType != null) {
				collector.submitModel(this.detail, state, poseStack, this.detailType, state.lightCoords, OverlayTexture.NO_OVERLAY, VehicleModels.detail(false), null,
					state.outlineColor);
			}

			poseStack.popPose();
		}

		super.submit(state, poseStack, collector, camera);
	}
}
