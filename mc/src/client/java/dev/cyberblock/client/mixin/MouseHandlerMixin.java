package dev.cyberblock.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.cyberblock.client.ClientInput;
import dev.cyberblock.client.HostCursor;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Screens ask the mouse handler where the cursor is (hover, tooltips): while the host drives it, it is the host's. And while the
 * host drives the game, its attack key holds the mouse: block breaking continues over ticks (ClientInput.grabsMouse, plan Q-B2).
 */
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

	@Inject(method = "isMouseGrabbed()Z", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostHoldsTheMouse(final CallbackInfoReturnable<Boolean> cir) {
		if (ClientInput.grabsMouse()) {
			cir.setReturnValue(true);
		}
	}
}
