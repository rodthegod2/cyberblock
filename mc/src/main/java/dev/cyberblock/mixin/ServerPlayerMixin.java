// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.PlayerEffects;
import java.util.Collection;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
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
 * Minecraft moving the player (/tp) moves the host's player too; otherwise the host would pull it back. Layer 5: Steve's status
 * effects go to the host as they change (PlayerEffects).
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"))
	private void cyberblock$teleported(final TeleportTransition transition, final CallbackInfoReturnable<ServerPlayer> cir) {
		if (Cyberblock.active) {
			Vec3 p = transition.position();
			Cyberblock.events.accept(String.format(Locale.ROOT, "{\"t\":\"pteleport\",\"pos\":[%.3f,%.3f,%.3f]}", p.x, p.y, p.z));
		}
	}

	@Inject(method = "onEffectAdded", at = @At("TAIL"))
	private void cyberblock$effectAdded(final MobEffectInstance effect, final @Nullable Entity source, final CallbackInfo ci) {
		PlayerEffects.changed(effect, false);
	}

	@Inject(method = "onEffectUpdated", at = @At("TAIL"))
	private void cyberblock$effectUpdated(final MobEffectInstance effect, final boolean doRefreshAttributes, final @Nullable Entity source, final CallbackInfo ci) {
		PlayerEffects.changed(effect, false);
	}

	@Inject(method = "onEffectsRemoved", at = @At("TAIL"))
	private void cyberblock$effectsRemoved(final Collection<MobEffectInstance> effects, final CallbackInfo ci) {
		for (MobEffectInstance effect : effects) {
			PlayerEffects.changed(effect, true);
		}
	}
}
