// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aim along the host camera's ray (what is under the crosshair): in third person the camera sits behind and beside the
 * player, so the ray starts level with the player and reaches `range` past. In both views the ray stops a margin past the Night
 * City surface under the crosshair (HostState.aimLimit), so nothing behind a Cyberpunk wall is targeted; while attack is held the
 * block being broken stays targeted for a moment when the limit dips short of it (HostState.stickyTarget, plan Q-B1).
 */
@Mixin(Entity.class)
abstract class EntityPickMixin {
	@Inject(method = "pick(DFZ)Lnet/minecraft/world/phys/HitResult;", at = @At("HEAD"), cancellable = true)
	private void cyberblock$cameraRay(final double range, final float a, final boolean withLiquids, final CallbackInfoReturnable<HitResult> cir) {
		HostState.Pose p = HostState.frame();
		if (p == null || !((Object) this instanceof LocalPlayer self)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 from = camera.position();
		Vec3 dir = new Vec3(camera.forwardVector()).normalize();
		double along = p.firstPerson() ? 0.0 : Math.max(0.0, self.getEyePosition(a).subtract(from).dot(dir));
		Vec3 start = from.add(dir.scale(Math.max(0.0, along - 0.4)));
		ClipContext.Fluid fluid = withLiquids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
		HitResult hit = self.level().clip(new ClipContext(start, from.add(dir.scale(HostState.aimLimit(along + range))), ClipContext.Block.OUTLINE, fluid, self));
		boolean attack = minecraft.options.keyAttack.isDown();
		long now = System.nanoTime();
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			HostState.picked(block.getBlockPos(), attack, now);
		} else {
			BlockPos sticky = HostState.stickyTarget(attack, pos -> !self.level().getBlockState(pos).isAir(), now);
			if (sticky != null
				&& self.level().clip(new ClipContext(start, from.add(dir.scale(along + range)), ClipContext.Block.OUTLINE, fluid, self)) instanceof BlockHitResult full
				&& full.getType() == HitResult.Type.BLOCK && full.getBlockPos().equals(sticky)) {
				hit = full;
			} else {
				HostState.picked(null, attack, now);
			}
		}

		cir.setReturnValue(hit);
	}
}
