package dev.cyberblock.cyber.mixin;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smart arrows (plan section 1.5): a flying arrow of a player with the perk steers toward proxies, on the server. */
@Mixin(AbstractArrow.class)
abstract class AbstractArrowMixin {
	@Shadow
	protected abstract boolean isInGround();

	@Inject(method = "tick", at = @At("HEAD"))
	private void cyberblock$smartArrows(final CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level() instanceof ServerLevel level && !this.isInGround() && self.getOwner() instanceof ServerPlayer player
			&& CyberData.has(player, Perk.SMART_ARROWS)) {
			PerkEffects.steer(level, self);
		}
	}
}
