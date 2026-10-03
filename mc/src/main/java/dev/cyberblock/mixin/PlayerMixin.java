// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.mixin;

import dev.cyberblock.Cyberblock;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The host moves the player, so neither side should collide it with blocks: host terrain arrives as barriers
 * that the player can end up inside, and the server would otherwise reject those moves. Gliding (layer 4) Minecraft moves
 * him: then vanilla physics, collisions and elytra damage apply. While the host moves him his falls are V's: V's fall damage
 * reaches Steve as the host's "fall" damage (HostDamage), so Minecraft's own fall distance and fall damage would count it twice.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void cyberblock$ghost(final CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Cyberblock.active && !self.isFallFlying()) {
			((Entity) self).noPhysics = true;
			self.resetFallDistance();
		}
	}

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void cyberblock$hostFalls(final double fallDistance, final float damageModifier, final DamageSource damageSource,
		final CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (Cyberblock.active && self.noPhysics && !self.isSpectator()) {
			self.resetFallDistance();
			cir.setReturnValue(false);
		}
	}
}
