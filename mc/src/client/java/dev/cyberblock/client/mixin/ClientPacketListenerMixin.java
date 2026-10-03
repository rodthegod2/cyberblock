package dev.cyberblock.client.mixin;

import dev.cyberblock.client.Flight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Knockback the server gives Steve goes to Flight (plan retest2 R5), which decides on the next tick whether it launches him off V's
 * ground: Steve hangs at V with no physics, so vanilla's push alone would be undone at once. Minecraft 26.3 ClientPacketListener:
 * handleExplosion ends with packet.playerKnockback().ifPresent(player::pushFromExplosion); handleSetEntityMotion calls
 * entity.lerpMotion(packet.movement()) for packet.id(). Both run on the client thread (ensureRunningOnSameThread returns from the
 * network thread before the TAIL).
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void cyberblock$launchFromExplosion(final ClientboundExplodePacket packet, final CallbackInfo ci) {
		packet.playerKnockback().ifPresent(v -> Flight.impulse(v, true));
	}

	@Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
	private void cyberblock$launchFromMotion(final ClientboundSetEntityMotionPacket packet, final CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && packet.id() == player.getId()) {
			Flight.impulse(packet.movement(), false);
		}
	}
}
