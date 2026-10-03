// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aim along the host camera's ray (what is under the crosshair): in third person the camera sits behind and beside the
 * player, so the ray starts level with the player and reaches `range` past. In both views the ray stops at the Night
 * City surface under the crosshair (HostState.aimLimit), so nothing behind a Cyberpunk wall is targeted.
 */
@Mixin(Entity.class)
abstract class EntityPickMixin {
	@Inject(method = "pick(DFZ)Lnet/minecraft/world/phys/HitResult;", at = @At("HEAD"), cancellable = true)
	private void cyberblock$cameraRay(final double range, final float a, final boolean withLiquids, final CallbackInfoReturnable<HitResult> cir) {
		HostState.Pose p = HostState.frame();
		if (p == null || !((Object) this instanceof LocalPlayer self)) {
			return;
		}

		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 from = camera.position();
		Vec3 dir = new Vec3(camera.forwardVector()).normalize();
		double along = p.firstPerson() ? 0.0 : Math.max(0.0, self.getEyePosition(a).subtract(from).dot(dir));
		Vec3 start = from.add(dir.scale(Math.max(0.0, along - 0.4)));
		Vec3 end = from.add(dir.scale(HostState.aimLimit(along + range)));
		ClipContext.Fluid fluid = withLiquids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
		cir.setReturnValue(self.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, fluid, self)));
	}
}
