// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.mixin;

import dev.cyberblock.PlayerEffects;
import dev.cyberblock.Wire;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.Collection;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft moving the player (/tp) moves his host's V too ({"t":"pteleport"} to his own session); otherwise the host would pull it
 * back. Layer 5: Steve's status effects go to his host as they change (PlayerEffects). A player locked by the join password
 * (JoinLock) drops nothing with Q and opens no container, whatever opened it.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"))
	private void cyberblock$teleported(final TeleportTransition transition, final CallbackInfoReturnable<ServerPlayer> cir) {
		HostSession s = HostSessions.of((ServerPlayer) (Object) this);
		if (s != null && s.active()) {
			Vec3 p = transition.position();
			s.send(Wire.pteleport(p.x, p.y, p.z));
		}
	}

	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void cyberblock$lockedDropsNothing(final boolean all, final CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (JoinLock.locked(self)) {
			self.containerMenu.sendAllDataToRemote(); // the client already took the item out of the hand
			ci.cancel();
		}
	}

	@Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
	private void cyberblock$lockedOpensNothing(final MenuProvider menu, final CallbackInfoReturnable<OptionalInt> cir) {
		if (JoinLock.locked((ServerPlayer) (Object) this)) {
			cir.setReturnValue(OptionalInt.empty());
		}
	}

	@Inject(method = "onEffectAdded", at = @At("TAIL"))
	private void cyberblock$effectAdded(final MobEffectInstance effect, final @Nullable Entity source, final CallbackInfo ci) {
		PlayerEffects.changed((ServerPlayer) (Object) this, effect, false);
	}

	@Inject(method = "onEffectUpdated", at = @At("TAIL"))
	private void cyberblock$effectUpdated(final MobEffectInstance effect, final boolean doRefreshAttributes, final @Nullable Entity source, final CallbackInfo ci) {
		PlayerEffects.changed((ServerPlayer) (Object) this, effect, false);
	}

	@Inject(method = "onEffectsRemoved", at = @At("TAIL"))
	private void cyberblock$effectsRemoved(final Collection<MobEffectInstance> effects, final CallbackInfo ci) {
		for (MobEffectInstance effect : effects) {
			PlayerEffects.changed((ServerPlayer) (Object) this, effect, true);
		}
	}
}
