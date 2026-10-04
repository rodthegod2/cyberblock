package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cyberblock.ProxyDamage;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lynx paws (plan retest2 section 1.7): Steve's steps make no sound and raise no game events (sculk sensors, wardens), as
 * when he sneaks on the ground. Player.getMovementEmission (26.3) on both sides: the client plays his own steps.
 * <p>
 * The swing's charge (leftover bug QG, the Gorilla Arms implant): 26.3 Player.attack reads getAttackStrengthScale(0.5F), then
 * resets the ticker (onAttack) before target.hurtOrSimulate, so a proxy's hurtServer cannot ask for it. It is kept in ProxyDamage
 * for the length of the server's attack.
 */
@Mixin(Player.class)
abstract class CyberPlayerMixin {
	@ModifyReturnValue(method = "getMovementEmission", at = @At("RETURN"))
	private Entity.MovementEmission cyberblock$lynxPaws(final Entity.MovementEmission emission) {
		return CyberData.has((Player) (Object) this, Cyberware.LYNX_PAWS) ? Entity.MovementEmission.NONE : emission;
	}

	@ModifyExpressionValue(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAttackStrengthScale(F)F"))
	private float cyberblock$swingCharge(final float scale) {
		if ((Object) this instanceof ServerPlayer) {
			ProxyDamage.swing(this, ProxyDamage.strongSwing(scale));
		}

		return scale;
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void cyberblock$swingEnd(final Entity target, final CallbackInfo ci) {
		ProxyDamage.endSwing(this);
	}
}
