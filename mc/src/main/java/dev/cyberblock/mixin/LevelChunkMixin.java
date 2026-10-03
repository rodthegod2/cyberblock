package dev.cyberblock.mixin;

import dev.cyberblock.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change on the server (placed, broken, blown up) with the state it replaced, so the host can mirror the solid
 * ones as collision boxes. The chunk is where the old state is known: setBlockState returns it, or null when nothing changed.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private void cyberblock$blockChanged(final BlockPos pos, final BlockState state, final int flags, final CallbackInfoReturnable<BlockState> cir) {
		BlockState old = cir.getReturnValue();
		if (old != null && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
			WorldBridge.onBlockChanged(level, pos, old, state);
		}
	}
}
