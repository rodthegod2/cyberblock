package dev.cyberblock.cyber.mixin;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Field medic (plan section 1.5): food gives half its nutrition and saturation again. */
@Mixin(FoodProperties.class)
abstract class FoodPropertiesMixin {
	@Inject(method = "onConsume", at = @At("TAIL"))
	private void cyberblock$fieldMedic(final Level level, final LivingEntity user, final ItemStack stack, final Consumable consumable, final CallbackInfo ci) {
		if (user instanceof ServerPlayer player && CyberData.has(player, Perk.FIELD_MEDIC)) {
			FoodProperties self = (FoodProperties) (Object) this;
			player.getFoodData().eat(new FoodProperties(PerkRules.extraNutrition(self.nutrition()), PerkRules.extraSaturation(self.saturation()), self.canAlwaysEat()));
		}
	}
}
