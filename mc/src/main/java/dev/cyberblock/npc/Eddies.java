package dev.cyberblock.npc;

import dev.cyberblock.device.Shops;
import dev.cyberblock.device.block.DeviceBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft eddies (megaplan section 1.16) in a player's inventory: {@code cyberblock:eddies} and {@code cyberblock:eddie_chip}
 * (worth 100), paid eddies first, then as few chips as cover the rest, the change back in eddies (Phase 4's Shops.Payment). The
 * server counts; a client never states a balance. Server thread.
 */
final class Eddies {
	/** A sell or hand-in row's second line when only copies the player would lose something on are there. */
	static final String KEPT = "Only enchanted, named or worn copies: those are kept";

	private Eddies() {
	}

	static long balance(final ServerPlayer player) {
		Inventory inv = player.getInventory();
		return count(inv, DeviceBlocks.EDDIES) + (long) count(inv, DeviceBlocks.EDDIE_CHIP) * Shops.Payment.CHIP;
	}

	/** The balance for a screen (capped at int). */
	static int shown(final ServerPlayer player) {
		return (int) Math.min(balance(player), Integer.MAX_VALUE);
	}

	/** Takes the price (creative pays nothing); false, and nothing taken, when the player cannot pay. */
	static boolean pay(final ServerPlayer player, final int price) {
		if (price <= 0 || player.isCreative()) {
			return true;
		}

		if (DeviceBlocks.EDDIES == null) {
			return false;
		}

		Inventory inv = player.getInventory();
		Shops.Payment pay = Shops.Payment.of(count(inv, DeviceBlocks.EDDIES), count(inv, DeviceBlocks.EDDIE_CHIP), price);
		if (pay == null) {
			return false;
		}

		take(inv, DeviceBlocks.EDDIES, pay.eddies());
		take(inv, DeviceBlocks.EDDIE_CHIP, pay.chips());
		give(player, DeviceBlocks.EDDIES, pay.change());
		return true;
	}

	/** Pays n eddies out (chips for each whole 100, the rest in eddies). */
	static void earn(final ServerPlayer player, final int n) {
		if (DeviceBlocks.EDDIE_CHIP != null && n >= Shops.Payment.CHIP) {
			give(player, DeviceBlocks.EDDIE_CHIP, n / Shops.Payment.CHIP);
			give(player, DeviceBlocks.EDDIES, n % Shops.Payment.CHIP);
		} else {
			give(player, DeviceBlocks.EDDIES, n);
		}
	}

	static int count(final Inventory inv, final @Nullable Item item) {
		if (item == null) {
			return 0;
		}

		long n = 0;
		for (ItemStack s : inv) {
			if (s.getItem() == item) {
				n += s.getCount();
			}
		}

		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	/** A copy a player can sell or hand in without losing anything: no enchantment, no name of its own, no wear. */
	static boolean plain(final ItemStack s) {
		return !s.isEnchanted() && s.getCustomName() == null && !s.isDamaged();
	}

	/**
	 * Copies of an item to sell or hand in: only in the hotbar and backpack (never worn armour or the off hand), and only plain ones
	 * unless {@code all}.
	 */
	static int goods(final Inventory inv, final Item item, final boolean all) {
		long n = 0;
		for (ItemStack s : inv.getNonEquipmentItems()) {
			if (s.getItem() == item && (all || plain(s))) {
				n += s.getCount();
			}
		}

		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	/** Takes n plain copies from the hotbar and backpack (the caller checked {@link #goods} first). */
	static void takeGoods(final Inventory inv, final Item item, final int n) {
		int left = n;
		for (ItemStack s : inv.getNonEquipmentItems()) {
			if (left <= 0) {
				break;
			}

			if (s.getItem() == item && plain(s)) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}

		inv.setChanged();
	}

	/** The row's note when only enchanted, named or worn copies would make up the count ("" otherwise): those are kept. */
	static String kept(final Inventory inv, final Item item, final int need) {
		return goods(inv, item, false) < need && goods(inv, item, true) >= need ? KEPT : "";
	}

	static void take(final Inventory inv, final @Nullable Item item, final int n) {
		int left = n;
		for (int i = 0; i < inv.getContainerSize() && left > 0 && item != null; i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() == item) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}

		inv.setChanged();
	}

	/** Into the inventory, the rest dropped at the player's feet. */
	static void give(final ServerPlayer player, final @Nullable Item item, final int n) {
		if (item == null) {
			return;
		}

		int max = Math.max(1, item.getDefaultMaxStackSize());
		for (int left = n; left > 0; left -= max) {
			player.getInventory().placeItemBackInInventory(new ItemStack(item, Math.min(left, max)), Prediction.SERVER_ONLY);
		}
	}
}
