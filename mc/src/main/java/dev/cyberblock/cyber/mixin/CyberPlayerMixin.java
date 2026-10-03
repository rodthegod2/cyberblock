package dev.cyberblock.cyber.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lynx paws (plan retest2 section 1.7): Steve's steps make no sound and raise no game events (sculk sensors, wardens), as
 * when he sneaks on the ground. Player.getMovementEmission (26.3) on both sides: the client plays his own steps.
 */
@Mixin(Player.class)
abstract class CyberPlayerMixin {
	@ModifyReturnValue(method = "getMovementEmission", at = @At("RETURN"))
	private Entity.MovementEmission cyberblock$lynxPaws(final Entity.MovementEmission emission) {
		return CyberData.has((Player) (Object) this, Cyberware.LYNX_PAWS) ? Entity.MovementEmission.NONE : emission;
	}
}
