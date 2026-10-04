package dev.cyberblock.mixin;

import dev.cyberblock.net.JoinLock;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player locked by the join password picks nothing up: items, arrows and experience stay where they are. */
@Mixin({ItemEntity.class, AbstractArrow.class, ExperienceOrb.class})
abstract class PickupMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void cyberblock$lockedPicksNothingUp(final Player player, final CallbackInfo ci) {
		if (JoinLock.locked(player)) {
			ci.cancel();
		}
	}
}
