package dev.cyberblock.client.mixin;

import dev.cyberblock.client.replica.ReplicaRender;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Megaplan task 3-C1: the chunk-section mesher reads every block, fluid and neighbour of a section through this region (26.3:
 * {@code getBlockState} and {@code getFluidState} both read {@code SectionCopy.getBlockState}); while Cyberpunk is the picture a
 * replica block reads as air here, so it gets no faces and its neighbours keep theirs. See ReplicaRender.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin {
	@Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/SectionCopy;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState cyberblock$hideReplica(final SectionCopy section, final BlockPos pos) {
		BlockState state = section.getBlockState(pos);
		if (ReplicaRender.hides(state)) {
			ReplicaRender.hookUsed();
			return Blocks.AIR.defaultBlockState();
		}

		return state;
	}
}
