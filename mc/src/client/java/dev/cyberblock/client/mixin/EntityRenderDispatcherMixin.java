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

/** In a vehicle Steve isn't drawn at all (not his body, armour, held items or shadow): Cyberpunk shows who is driving. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hideSteve(final Entity entity, final Frustum culler, final double camX, final double camY, final double camZ,
		final float partialTicks, final CallbackInfoReturnable<Boolean> cir) {
		if (HostState.vehicle() && entity == Minecraft.getInstance().player) {
			cir.setReturnValue(false);
		}
	}
}
