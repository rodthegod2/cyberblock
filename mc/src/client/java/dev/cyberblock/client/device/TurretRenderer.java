package dev.cyberblock.client.device;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.client.replica.ReplicaRender;
import dev.cyberblock.device.block.DeviceBlockEntity;
import dev.cyberblock.device.block.FacingDeviceBlock;
import dev.cyberblock.device.block.TurretBlock;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The turret's head (section 1.11 turrets): the block is the base, this draws the head and barrel on top, turned toward the target
 * and tinted by the mode: active red and tracking the nearest player within {@link TurretBlock#RANGE}, friendly green and sweeping,
 * off grey with the barrel drooping. The aim is client-side (every client sees the same nearest player). Nothing while Cyberpunk is
 * the picture (an active host). The head is a tint over the player's own vanilla iron block texture: no art of ours.
 */
final class TurretRenderer implements BlockEntityRenderer<DeviceBlockEntity, TurretRenderer.State> {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/block/iron_block.png");
	private static final RenderType RENDER_TYPE = RenderTypes.entitySolid(TEXTURE);
	static final int OFF = 0xFF707070;
	static final int ACTIVE = 0xFFD83838;
	static final int FRIENDLY = 0xFF38C050;
	static final int BARREL = 0xFF2A2A2A;
	/** The pitch an off turret's barrel droops to. */
	static final float DROOP = 30.0F;
	/** A 1 m cube standing on its centre (x and z -0.5..0.5, y 0..1), stretched into each box of the head. */
	private final Model<State> cube;

	static final class State extends BlockEntityRenderState {
		float yaw;
		float pitch;
		int colour;
	}

	TurretRenderer(final BlockEntityRendererProvider.Context context) {
		MeshDefinition mesh = new MeshDefinition();
		mesh.getRoot().addOrReplaceChild("box", CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, 0.0F, -8.0F, 16.0F, 16.0F, 16.0F), PartPose.ZERO);
		// a 64 x 32 texture is exactly the cube's unfolded faces: the 16 x 16 iron is stretched over all six
		this.cube = new Model<>(LayerDefinition.create(mesh, 64, 32).bakeRoot(), RenderTypes::entitySolid) {
		};
	}

	/** The turret block entity's client tick: turn the head a little toward where it should look. */
	static void tick(final Level level, final BlockPos pos, final BlockState state, final DeviceBlockEntity turret) {
		turret.yawO = turret.yaw;
		turret.pitchO = turret.pitch;
		turret.age++;
		TurretBlock.Mode mode = state.getValue(TurretBlock.MODE);
		Direction facing = state.getValue(FacingDeviceBlock.FACING);
		Vec3 head = Vec3.atCenterOf(pos).add(0.0, 0.25, 0.0);
		AbstractClientPlayer target = mode == TurretBlock.Mode.ACTIVE ? target(level, head) : null;
		float yaw;
		float pitch;
		if (target != null) {
			yaw = TurretBlock.yawToward(target.getX() - head.x, target.getZ() - head.z);
			pitch = TurretBlock.pitchToward(Math.hypot(target.getX() - head.x, target.getZ() - head.z), target.getEyeY() - 0.3 - head.y);
		} else if (mode == TurretBlock.Mode.OFF) {
			yaw = facing.toYRot();
			pitch = DROOP;
		} else {
			yaw = TurretBlock.sweep(facing, turret.age);
			pitch = 0.0F;
		}

		turret.yaw = TurretBlock.approach(turret.yaw, yaw, TurretBlock.TURN_SPEED);
		turret.pitch += Mth.clamp(pitch - turret.pitch, -TurretBlock.TURN_SPEED, TurretBlock.TURN_SPEED);
	}

	private static @Nullable AbstractClientPlayer target(final Level level, final Vec3 head) {
		AbstractClientPlayer best = null;
		double bestDistance = TurretBlock.RANGE * TurretBlock.RANGE;
		for (var player : level.players()) {
			if (player instanceof AbstractClientPlayer p && !p.isSpectator() && !p.isCreative()) {
				double d = p.distanceToSqr(head);
				if (d < bestDistance) {
					bestDistance = d;
					best = p;
				}
			}
		}

		return best;
	}

	static int colour(final TurretBlock.Mode mode) {
		return switch (mode) {
			case ACTIVE -> ACTIVE;
			case FRIENDLY -> FRIENDLY;
			case OFF -> OFF;
		};
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final DeviceBlockEntity turret, final State state, final float partialTicks, final Vec3 cameraPos,
		final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(turret, state, partialTicks, cameraPos, breakProgress);
		state.yaw = Mth.rotLerp(partialTicks, turret.yawO, turret.yaw);
		state.pitch = Mth.lerp(partialTicks, turret.pitchO, turret.pitch);
		BlockState block = turret.getBlockState();
		state.colour = block.hasProperty(TurretBlock.MODE) ? colour(block.getValue(TurretBlock.MODE)) : OFF;
	}

	@Override
	public boolean shouldRender(final DeviceBlockEntity turret, final Vec3 cameraPos) {
		return !ReplicaRender.hiding() && BlockEntityRenderer.super.shouldRender(turret, cameraPos);
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		poseStack.pushPose();
		// the head turns about the middle of the block's upper half; +z is the barrel's way (the way a yaw faces)
		poseStack.translate(0.5, 0.75, 0.5);
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		poseStack.rotateDegrees(Axis.XP, state.pitch);
		box(state, poseStack, collector, 0.0, -0.1875, 0.0, 0.5F, 0.375F, 0.5F, state.colour);
		box(state, poseStack, collector, 0.0, -0.0625, 0.5, 0.125F, 0.125F, 0.5F, BARREL);
		poseStack.popPose();
	}

	/** One box of the head, bottom centre (x, y, z), size (w, h, d) in blocks. */
	private void box(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final double x, final double y, final double z,
		final float w, final float h, final float d, final int colour) {
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		poseStack.scale(w, h, d);
		collector.submitModel(this.cube, state, poseStack, RENDER_TYPE, state.lightCoords, OverlayTexture.NO_OVERLAY, colour, null, 0);
		poseStack.popPose();
	}
}
