package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.JoinLock;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
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
	 * every tick. The host role is granted only to whitelisted players or, on a server with a join password, players who passed it
	 * (megaplan section 1.6), so they move freely like the owner.
	 */
	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostMovesWithV(final boolean elytra, final CallbackInfoReturnable<Boolean> cir) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (Cyberblock.hostActive(self.player)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * A player locked by the join password cannot move items in its own inventory (throwing one out with Q or by clicking outside
	 * the screen is a drop) or take any from the creative tabs; the client's guess is put back. Injected after the hop to the
	 * server thread (PacketUtils.ensureRunningOnSameThread is the first call of both handlers, javap of the 26.3 jar).
	 */
	@Inject(method = "handleContainerClick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
		cancellable = true)
	private void cyberblock$lockedClicksNothing(final ServerboundContainerClickPacket packet, final CallbackInfo ci) {
		this.cyberblock$lockedInventory(ci);
	}

	@Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
		cancellable = true)
	private void cyberblock$lockedTakesNothing(final ServerboundSetCreativeModeSlotPacket packet, final CallbackInfo ci) {
		this.cyberblock$lockedInventory(ci);
	}

	private void cyberblock$lockedInventory(final CallbackInfo ci) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (JoinLock.locked(self.player)) {
			self.player.containerMenu.sendAllDataToRemote();
			ci.cancel();
		}
	}
}
