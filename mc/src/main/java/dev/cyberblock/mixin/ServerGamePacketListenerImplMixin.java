package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the host moves Steve he has no physics (PlayerMixin), so the server never finds a block under him when his client says
 * he stands on V's ground: vanilla then resends the blocks below every 10 s and logs "standing on air - force-sending blocks
 * below" (in-game 2026-10-03). There is nothing to resync.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostCarries(final CallbackInfo ci) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (self.player.noPhysics && Cyberblock.hostActive(self.player)) {
			ci.cancel();
		}
	}

	/**
	 * An active host's Steve goes wherever V goes (PlayerSync), in jumps a player could never make: a far F9, fast travel, an elevator.
	 * Vanilla skips its "moved too quickly" and "moved wrongly" checks only for the singleplayer/LAN owner (shouldCheckPlayerMovement
	 * starts with isSingleplayerOwner, javap of the 26.3 jar), so on a dedicated server the host was pulled back to where he joined
	 * every tick. The host role is granted only to whitelisted players (megaplan section 1.6), so they move freely like the owner.
	 */
	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostMovesWithV(final boolean elytra, final CallbackInfoReturnable<Boolean> cir) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (Cyberblock.hostActive(self.player)) {
			cir.setReturnValue(false);
		}
	}
}
