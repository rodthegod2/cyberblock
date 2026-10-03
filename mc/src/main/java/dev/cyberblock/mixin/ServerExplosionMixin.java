package dev.cyberblock.mixin;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Night City's ground and walls are barrier blocks that no explosion may remove. Vanilla gives barriers a blast resistance
 * of 3,600,000, but an explosion with its own immune-block list (data-driven ones) ignores resistance, so enforce it here.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void cyberblock$sparesBarriers(final CallbackInfoReturnable<List<BlockPos>> cir) {
		cir.getReturnValue().removeIf(pos -> this.level.getBlockState(pos).is(Blocks.BARRIER));
	}
}
