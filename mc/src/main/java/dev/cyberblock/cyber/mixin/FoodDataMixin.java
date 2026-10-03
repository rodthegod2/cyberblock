package dev.cyberblock.cyber.mixin;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Painkiller (plan section 1.5): natural regeneration twice as fast. When FoodData.tick is about to run one of its two
 * regeneration timers (every 10 ticks while saturated, every 80 while fed), the timer gets an extra tick first, so it
 * reaches its interval in half the time.
 */
@Mixin(FoodData.class)
abstract class FoodDataMixin {
	@Shadow private int foodLevel;
	@Shadow private float saturationLevel;
	@Shadow private int tickTimer;

	@Inject(method = "tick", at = @At("HEAD"))
	private void cyberblock$painkiller(final ServerPlayer player, final CallbackInfo ci) {
		boolean regenerating = player.isHurt() && (this.saturationLevel > 0.0F && this.foodLevel >= 20 || this.foodLevel >= 18)
			&& player.level().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
		if (regenerating && CyberData.has(player, Perk.PAINKILLER)) {
			this.tickTimer++;
		}
	}
}
