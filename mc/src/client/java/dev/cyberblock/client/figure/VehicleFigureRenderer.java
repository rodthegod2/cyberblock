package dev.cyberblock.client.figure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.figure.VehicleModel;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * A Night City vehicle as guests see it (plan 2026-10-04 tasks 3-F1 and 5-F1): one of the 12 vehicle models (VehicleModels) in the
 * paint the server chose (the host's colour, else vehicle_map's, else the model's), its lights glowing, a police light bar flashing
 * red and blue while the car is hostile (a chase), a red hit flash, the name tag in the threat colour; facing the way the host
 * reports, driving at its real speed, smoothed (FigureSmoothing); a destroyed one stays as a dark wreck for 5 s. Hidden on an active
 * host's own client, as people are.
 */
final class VehicleFigureRenderer extends EntityRenderer<ProxyEntity, VehicleFigureRenderer.State> {
	/** Every box is the player's own vanilla white concrete, tinted: no art of ours is needed for the shapes. */
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
	private static final RenderType RENDER_TYPE = RenderTypes.entitySolid(TEXTURE);
	private static final int RED_FLASH = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(true));
	/** A 1 m cube standing on its centre (x and z -0.5..0.5, y 0..1), stretched into each box of the model. */
	private final Model<State> cube;
	private final Map<Entity, FigureRenderer.Track> tracks = new WeakHashMap<>();

	static final class State extends EntityRenderState {
		float yRot;
		boolean dead;
		VehicleModel model = VehicleModel.SEDAN;
		int paint = VehicleModel.SEDAN.defaultColour();
		boolean chase;
		boolean flash;
	}

	VehicleFigureRenderer(final EntityRendererProvider.Context context) {
		super(context);
		MeshDefinition mesh = new MeshDefinition();
		mesh.getRoot().addOrReplaceChild("box", CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, 0.0F, -8.0F, 16.0F, 16.0F, 16.0F), PartPose.ZERO);
		// a 64 x 32 texture is exactly the cube's unfolded faces: the 16 x 16 concrete is stretched over all six
		this.cube = new Model<>(LayerDefinition.create(mesh, 64, 32).bakeRoot(), RenderTypes::entitySolid) {
		};
		this.shadowRadius = 1.2F;
	}

	@Override
	public boolean shouldRender(final ProxyEntity entity, final Frustum frustum, final double x, final double y, final double z, final float partialTicks) {
		return FigureRenderer.drawn() && super.shouldRender(entity, frustum, x, y, z, partialTicks);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final ProxyEntity entity, final State state, final float partialTicks) {
		// the model first: the shadow (worked out in super) is the model's
		state.model = FigureRules.vehicle(entity.lookId());
		super.extractRenderState(entity, state, partialTicks);
		state.yRot = Mth.rotLerp(partialTicks, entity.yRotO, entity.getYRot());
		state.dead = entity.dead();
		state.paint = entity.colour() >= 0 ? entity.colour() : state.model.defaultColour();
		state.chase = state.model.siren() && entity.hostile();
		state.flash = FigureEffects.of(entity).flash > 0;
		state.isInvisible = false;
		FigureRenderer.smooth(this.tracks, entity, state);
	}

	@Override
	protected float getShadowRadius(final State state) {
		return state.model == VehicleModel.BIKE ? 0.5F : state.model.flies() ? 1.6F : 1.2F;
	}

	/** The longest models (a semi is 7.5 m) reach well past the 3 m proxy box: it must not be culled while its ends are in view. */
	@Override
	protected AABB getBoundingBoxForCulling(final ProxyEntity entity, final float partialTicks) {
		return super.getBoundingBoxForCulling(entity, partialTicks).inflate(2.5, 2.0, 2.5);
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		poseStack.pushPose();
		// the model's +z is its front: the way a Minecraft yaw faces
		poseStack.rotateDegrees(Axis.YP, -state.yRot);
		int overlay = state.flash && !state.dead ? RED_FLASH : OverlayTexture.NO_OVERLAY;
		for (VehicleModels.Part p : VehicleModels.parts(state.model)) {
			int colour = VehicleModels.colour(p.role(), state.model, state.paint, state.dead, state.chase, state.ageInTicks);
			int light = VehicleModels.glows(p.role(), state.model, state.dead, state.chase) ? LightCoordsUtil.FULL_BRIGHT : state.lightCoords;
			poseStack.pushPose();
			poseStack.translate((p.x0() + p.x1()) / 2.0, p.y0(), (p.z0() + p.z1()) / 2.0);
			poseStack.scale((float) (p.x1() - p.x0()), (float) (p.y1() - p.y0()), (float) (p.z1() - p.z0()));
			collector.submitModel(this.cube, state, poseStack, RENDER_TYPE, light, overlay, colour, null, state.outlineColor);
			poseStack.popPose();
		}

		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	protected boolean shouldShowName(final ProxyEntity entity, final double distanceToCameraSq) {
		return FigureRules.showTag(entity.dead(), distanceToCameraSq);
	}

	@Override
	protected Component getNameTag(final ProxyEntity entity) {
		return FigureRules.tag(entity.label(), entity.threat(), entity.hostile(), true);
	}
}
