package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Steve sits in Night City's vehicles (plan retest3 M2, F3): he is drawn seated once the host says where V's seat is (cam.seat). Until
 * then (getting in and out, or a host without seat data) he is not drawn at all in a vehicle, since he would stand at V's feet.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hideSteve(final Entity entity, final Frustum culler, final double camX, final double camY, final double camZ,
		final float partialTicks, final CallbackInfoReturnable<Boolean> cir) {
		if (HostState.vehicle() && entity == Minecraft.getInstance().player) {
			HostState.Pose p = HostState.frame();
			if (p == null || !p.seat()) {
				cir.setReturnValue(false);
			}
		}
	}
}
