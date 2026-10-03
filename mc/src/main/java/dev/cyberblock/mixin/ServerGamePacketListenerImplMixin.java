package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the host moves Steve he has no physics (PlayerMixin), so the server never finds a block under him when his client says
 * he stands on V's ground: vanilla then resends the blocks below every 10 s and logs "standing on air - force-sending blocks
 * below" (in-game 2026-10-03). There is nothing to resync.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostCarries(final CallbackInfo ci) {
		if (Cyberblock.active && ((ServerGamePacketListenerImpl) (Object) this).player.noPhysics) {
			ci.cancel();
		}
	}
}
