package dev.cyberblock.mixin;

import dev.cyberblock.mode.overlord.Overlord;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An Overlord enderman (megaplan 8-O1) never picks up a block: the Overlord's mobs fight the match's players, they do not grief. */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
abstract class EndermanTakeBlockGoalMixin {
	@Shadow
	@Final
	private Enderman enderman;

	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void cyberblock$overlordKeepsBlocks(final CallbackInfoReturnable<Boolean> cir) {
		if (this.enderman.entityTags().contains(Overlord.TAG)) {
			cir.setReturnValue(false);
		}
	}
}
