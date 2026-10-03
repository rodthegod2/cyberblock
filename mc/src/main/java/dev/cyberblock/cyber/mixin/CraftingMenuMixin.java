package dev.cyberblock.cyber.mixin;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.PerkRules;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tinkerer (plan section 1.5): TNT and arrow recipes give twice as many. The crafting grid's result (crafting table and the
 * inventory's 2x2 both use this method) is doubled and sent to the client again.
 */
@Mixin(CraftingMenu.class)
abstract class CraftingMenuMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
	private static void cyberblock$tinkerer(final AbstractContainerMenu menu, final ServerLevel level, final Player player, final CraftingContainer container,
		final ResultContainer resultSlots, final @Nullable RecipeHolder<CraftingRecipe> recipeHint, final CallbackInfo ci) {
		ItemStack result = resultSlots.getItem(0);
		if (result.isEmpty() || !(result.is(Items.TNT) || result.is(ItemTags.ARROWS)) || !(player instanceof ServerPlayer serverPlayer)
			|| !CyberData.has(serverPlayer, Perk.TINKERER)) {
			return;
		}

		ItemStack doubled = result.copyWithCount(PerkRules.craftedCount(result.getCount(), result.getMaxStackSize()));
		resultSlots.setItem(0, doubled);
		menu.setRemoteSlot(0, doubled);
		serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, doubled));
	}
}
