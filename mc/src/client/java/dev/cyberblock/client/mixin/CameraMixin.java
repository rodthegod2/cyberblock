// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.FrameExporter;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.PlayerSync;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The host's camera replaces the player's: position, rotation (with roll), field of view and first/third person. */
@Mixin(Camera.class)
abstract class CameraMixin {
	private static final float DEG = (float) (Math.PI / 180.0);
	@Shadow @Final private static Vector3fc FORWARDS;
	@Shadow @Final private static Vector3fc UP;
	@Shadow @Final private static Vector3fc LEFT;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow @Final private Quaternionf rotation;
	@Shadow private float xRot;
	@Shadow private float yRot;
	@Shadow private boolean detached;
	@Shadow private int matrixPropertiesDirty;
	@Shadow private float depthFar;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "update", at = @At("HEAD"))
	private void cyberblock$beginFrame(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		HostState.beginFrame();
		PlayerSync.frame(deltaTracker.getGameTimeDeltaPartialTick(true));
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void cyberblock$hostCamera(final float partialTicks, final CallbackInfo ci) {
		HostState.Pose p = HostState.frame();
		if (p == null) {
			return;
		}

		this.xRot = p.pitch();
		this.yRot = p.yaw();
		this.rotation.rotationYXZ((float) Math.PI - p.yaw() * DEG, -p.pitch() * DEG, p.roll() * DEG);
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.matrixPropertiesDirty |= 3;
		// always the host's camera, gliding too (the host moves V to Steve): Minecraft's picture stays registered with Night City's
		double x = p.x(), y = p.y(), z = p.z();
		Entity player = Minecraft.getInstance().player;
		this.setPosition(x, y, z);
		// Cyberpunk pulls its camera in to the head against walls: then Minecraft's camera would be inside Steve's head
		boolean inside = player != null && player.getEyePosition(partialTicks).distanceToSqr(x, y, z) < 0.8 * 0.8;
		this.detached = !p.firstPerson() && !inside;
	}

	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostFov(final float partialTicks, final CallbackInfoReturnable<Float> cir) {
		HostState.Pose p = HostState.frame();
		if (p != null) {
			cir.setReturnValue(p.fov());
		}
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void cyberblock$planes(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		FrameExporter.setFar(this.depthFar);
	}
}
