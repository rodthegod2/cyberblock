package dev.cyberblock.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.cyberblock.client.HostCursor;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screens ask the mouse handler where the cursor is (hover, tooltips): while the host drives it, it is the host's. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostX(final Window window, final CallbackInfoReturnable<Double> cir) {
		if (HostCursor.active()) {
			cir.setReturnValue(HostCursor.x());
		}
	}

	@Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostY(final Window window, final CallbackInfoReturnable<Double> cir) {
		if (HostCursor.active()) {
			cir.setReturnValue(HostCursor.y());
		}
	}
}
