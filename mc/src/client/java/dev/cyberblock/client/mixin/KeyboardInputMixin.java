package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostState;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * V crouched (the host's cam.cr): Steve's sneak input is held, so he takes the crouch pose the vanilla way, on both sides (the
 * input packet carries it to the server). The host moves him, so sneaking changes nothing else.
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void cyberblock$hostCrouch(final CallbackInfo ci) {
		ClientInput self = (ClientInput) (Object) this;
		Input in = self.keyPresses;
		if (HostState.active() && HostState.crouching() && !in.shift()) {
			self.keyPresses = new Input(in.forward(), in.backward(), in.left(), in.right(), in.jump(), true, in.sprint());
		}
	}
}
