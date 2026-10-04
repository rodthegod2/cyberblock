package dev.cyberblock.client.figure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.figure.VehicleModel;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
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
import org.jspecify.annotations.Nullable;

/**
 * A Night City vehicle as guests see it (plan 2026-10-04 tasks 3-F1 and 5-F1): one of the 12 vehicle models (VehicleModels, 5A's UV
 * layout) with 5A's textures: the greyscale paint tinted by the vehicle's colour (the host's, else vehicle_map's), the detail over it,
 * and on the emergency vehicles the light bar's halves glowing in turn while the vehicle is hostile (a chase); a red hit flash; the
 * name tag in the threat colour. Facing the way the host reports, driving at its real speed, smoothed (FigureSmoothing); a destroyed
 * one stays as a dark wreck for 5 s. Without 5A's textures the boxes are tinted vanilla white concrete. Hidden on an active host's own
 * client, as people are.
 */
final class VehicleFigureRenderer extends EntityRenderer<ProxyEntity, VehicleFigureRenderer.State> {
	/** The paint without 5A's textures: the player's own vanilla white concrete, tinted. */
	private static final Identifier PLAIN = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
	private static final int RED_FLASH = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(true));
	/** Model pixels the detail and light layers stand proud of the paint, so they never fight it for depth. */
	private static final float DETAIL_GROW = 0.05F, LIGHTS_GROW = 0.1F;
	private static @Nullable Boolean loggedTextures;
	private final Map<VehicleModel, Looks> looks = new EnumMap<>(VehicleModel.class);
	private final Map<Entity, FigureRenderer.Track> tracks = new WeakHashMap<>();

	/** One model's three layers and their render types (detail and lights null without 5A's textures). */
	private record Looks(Model<State> paint, Model<State> detail, Model<State> lights, RenderType paintType, @Nullable RenderType detailType,
		RenderType @Nullable [] lightTypes) {
	}

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
		boolean textured = true;
		for (VehicleModel m : VehicleModel.values()) {
			Identifier paint = texture(m, ""), detail = texture(m, "_detail");
			boolean has = context.getResourceManager().getResource(paint).isPresent() && context.getResourceManager().getResource(detail).isPresent();
			textured &= has;
			RenderType[] lights = has && m.siren() ? new RenderType[] {RenderTypes.entityCutout(texture(m, "_lights_a")), RenderTypes.entityCutout(texture(m, "_lights_b"))} : null;
			this.looks.put(m, new Looks(model(m, 0.0F), model(m, DETAIL_GROW), model(m, LIGHTS_GROW), RenderTypes.entityCutout(has ? paint : PLAIN),
				has ? RenderTypes.entityCutout(detail) : null, lights));
		}

		if (!Boolean.valueOf(textured).equals(loggedTextures)) {
			loggedTextures = textured;
			Cyberblock.LOG.info(textured ? "figures: vehicles wear their textures (textures/entity/vehicle)" : "figures: vehicle textures missing, plain painted boxes");
		}

		this.shadowRadius = 1.2F;
	}

	private static Identifier texture(final VehicleModel m, final String suffix) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/vehicle/" + m.id() + suffix + ".png");
	}

	/** The model's boxes as one baked part (grown by `grow` model pixels each way, the UV unchanged). */
	private static Model<State> model(final VehicleModel m, final float grow) {
		MeshDefinition mesh = new MeshDefinition();
		for (VehicleModels.Part p : VehicleModels.parts(m)) {
			CubeListBuilder boxes = CubeListBuilder.create().texOffs(p.u(), p.v());
			for (int[] at : p.at()) {
				boxes.addBox(at[0], at[1], at[2], p.w(), p.h(), p.d(), new CubeDeformation(grow));
			}

			mesh.getRoot().addOrReplaceChild(p.name(), boxes, PartPose.ZERO);
		}

		ModelPart root = LayerDefinition.create(mesh, VehicleModels.TEXTURE, VehicleModels.TEXTURE).bakeRoot();
		return new Model<>(root, RenderTypes::entityCutout) {
		};
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
		state.chase = entity.hostile();
		state.flash = FigureEffects.of(entity).flash > 0;
		state.isInvisible = false;
		FigureRenderer.smooth(this.tracks, entity, state);
	}

	@Override
	protected float getShadowRadius(final State state) {
		return state.model == VehicleModel.BIKE ? 0.5F : state.model.flies() ? 1.6F : 1.2F;
	}

	/** The longest models (a truck is 5.5 m) reach past the 3 m proxy box: it must not be culled while its ends are in view. */
	@Override
	protected AABB getBoundingBoxForCulling(final ProxyEntity entity, final float partialTicks) {
		return super.getBoundingBoxForCulling(entity, partialTicks).inflate(2.5, 2.0, 2.5);
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		Looks look = this.looks.get(state.model);
		int overlay = state.flash && !state.dead ? RED_FLASH : OverlayTexture.NO_OVERLAY;
		poseStack.pushPose();
		// the model's +z is its front, the way a Minecraft yaw faces; 5A's boxes have y down and the nose at -z, at half scale
		poseStack.rotateDegrees(Axis.YP, -state.yRot);
		poseStack.rotateDegrees(Axis.XP, 180.0F);
		poseStack.scale(VehicleModels.SCALE, VehicleModels.SCALE, VehicleModels.SCALE);
		collector.submitModel(look.paint(), state, poseStack, look.paintType(), state.lightCoords, overlay,
			VehicleModels.paint(state.paint, state.dead), null, state.outlineColor);
		if (look.detailType() != null) {
			collector.submitModel(look.detail(), state, poseStack, look.detailType(), state.lightCoords, overlay, VehicleModels.detail(state.dead), null,
				state.outlineColor);
		}

		int half = VehicleModels.flashing(state.model, state.chase, state.dead, state.ageInTicks);
		if (half >= 0 && look.lightTypes() != null) {
			collector.submitModel(look.lights(), state, poseStack, look.lightTypes()[half], LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
				0xFFFFFFFF, null, state.outlineColor);
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
