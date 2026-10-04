package dev.cyberblock.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.OutlineRules;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Night City's ground and walls are barrier blocks that no explosion may remove (OutlineRules.sparedByExplosions, plan Q-B4).
 *
 * <p>Plan retest2 R5: while his host session is active a creative Steve "flies" only because PlayerSync hangs him at V, so the explosion's
 * knockback reaches him as it would a creative player on foot (Minecraft 26.3 ServerExplosion.hurtEntities only puts a player in
 * hitPlayers, the knockback sent with the explosion packet, when !player.isCreative() || !player.getAbilities().flying).
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void cyberblock$sparesBarriers(final CallbackInfoReturnable<List<BlockPos>> cir) {
		cir.getReturnValue().removeIf(pos -> OutlineRules.sparedByExplosions(this.level.getBlockState(pos)));
	}

	@ModifyExpressionValue(method = "hurtEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z"))
	private boolean cyberblock$creativeSteveIsKnockedBack(final boolean flying, @Local final Player player) {
		return flying && !Cyberblock.hostActive(player);
	}
}
