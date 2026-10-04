package dev.cyberblock.device;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.device.block.ShopWire;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A vending machine is a Minecraft shop (megaplan 4-M1, section 1.16; the Phase 0 amendment: buying is never a Cyberpunk action). A
 * right-click sends the machine's shop to the player in 4B's ShopWire shape ({@code cyberblock:ui t:"shop"}, read by 4B's
 * VendingScreen); a buy ({@code cyberblock:act t:"devact"} {@code {pos, act:"buy", line}}, the line id being the row index) is checked
 * and paid here: the row's limit, the price in Minecraft eddies ({@code cyberblock:eddies}, and {@code cyberblock:eddie_chip} worth
 * 100), the change back in eddies, the items into the inventory (the rest dropped at the player's feet). The client is never trusted
 * with a price or a balance. After a buy the shop goes out again with the new balance, the counts and the result code.
 */
final class Vending {
	static final String EDDIES = "cyberblock:eddies", CHIP = "cyberblock:eddie_chip";
	private static final Shops sales = new Shops();
	private static boolean noEddiesLogged;

	private Vending() {
	}

	static void clear() {
		sales.clear();
	}

	/** A ShopWire line id back to its row, or -1. */
	static int row(final @Nullable String line) {
		if (line == null || !line.matches("\\d{1,2}")) {
			return -1;
		}

		int r = Integer.parseInt(line);
		return r < Shops.MAX_ROWS ? r : -1;
	}

	/** The machine's shop id and shop, or null (and the player told) when it has none. */
	private record Picked(Identifier id, Shops.Shop shop) {
	}

	private static @Nullable Picked pick(final ServerPlayer player, final DeviceRegistry.Tracked t) {
		DeviceMap.Entry e = DeviceMap.entry(t.device().cls(), t.device().type());
		Identifier id = Shops.pick(e.shops(), t.device().id());
		Shops.Shop shop = id == null ? null : Shops.get(id);
		if (shop == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.shop.empty", "Nothing for sale here"));
			return null;
		}

		return new Picked(id, shop);
	}

	private static String machine(final DeviceRegistry.Tracked t) {
		return t.key().owner() + "/" + t.device().idText();
	}

	static void open(final ServerPlayer player, final DeviceRegistry.Tracked t, final BlockPos pos) {
		Picked p = pick(player, t);
		if (p != null) {
			send(player, t, pos, p.shop(), "");
		}
	}

	/** The shop as this player sees it now, with the result of its last buy ("" none, ok, poor, stock, full, gone). */
	private static void send(final ServerPlayer player, final DeviceRegistry.Tracked t, final BlockPos pos, final Shops.Shop shop, final String result) {
		List<ShopWire.Line> lines = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (int i = 0; i < shop.stock().size() && i < ShopWire.MAX_LINES; i++) {
			Shops.Row r = shop.stock().get(i);
			int left = sales.left(machine(t), shop, i, now);
			lines.add(new ShopWire.Line(Integer.toString(i), r.item(), r.count(), r.price(), left == Integer.MAX_VALUE ? -1 : Math.min(left, ShopWire.MAX_STOCK)));
		}

		int eddies = (int) Math.min(balance(player), Integer.MAX_VALUE);
		// the title stays "": ShopWire carries text, not a lang key; the screen shows its own "Vending machine"
		ServerNet.ui(player, ShopWire.T_SHOP, ShopWire.shop(new ShopWire.Shop(pos.getX(), pos.getY(), pos.getZ(), "", eddies, lines, result)));
	}

	static void buy(final ServerPlayer player, final DeviceRegistry.Tracked t, final BlockPos pos, final int row, final int times) {
		Picked p = pick(player, t);
		if (p == null) {
			return;
		}

		Shops.Shop shop = p.shop();
		if (row >= shop.stock().size()) {
			send(player, t, pos, shop, "gone");
			return;
		}

		Shops.Row r = shop.stock().get(row);
		long now = System.currentTimeMillis();
		Item item = item(r.item());
		Item eddies = item(EDDIES), chip = item(CHIP);
		if (item == null || eddies == null) {
			if (eddies == null && !noEddiesLogged) {
				noEddiesLogged = true;
				Cyberblock.LOG.warn("devices: no {} item is registered: vending machines cannot sell", EDDIES);
			}

			send(player, t, pos, shop, "gone");
			return;
		}

		if (sales.left(machine(t), shop, row, now) < times) {
			send(player, t, pos, shop, "stock");
			return;
		}

		int price = r.price() * times;
		Inventory inv = player.getInventory();
		Shops.Payment pay = Shops.Payment.of(count(inv, eddies), chip == null ? 0 : count(inv, chip), price);
		if (pay == null) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.shop.no_funds", "Not enough eddies: you need %s", price));
			send(player, t, pos, shop, "poor");
			return;
		}

		take(inv, eddies, pay.eddies());
		if (chip != null) {
			take(inv, chip, pay.chips());
		}

		give(inv, eddies, pay.change());
		give(inv, item, r.count() * times);
		sales.sold(machine(t), shop, row, times, now);
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.shop.bought", "Bought %s x%s for %s eddies", new ItemStack(item).getHoverName(),
			r.count() * times, price));
		Cyberblock.LOG.info("devices: {} bought {} x{} for {} eddies ({} at {} {} {})", player.getPlainTextName(), r.item(), r.count() * times, price, p.id(),
			pos.getX(), pos.getY(), pos.getZ());
		send(player, t, pos, shop, "ok");
	}

	/** The player's eddies: eddies plus 100 per chip. */
	static long balance(final ServerPlayer player) {
		Item eddies = item(EDDIES), chip = item(CHIP);
		Inventory inv = player.getInventory();
		return (eddies == null ? 0L : count(inv, eddies)) + (chip == null ? 0L : (long) count(inv, chip) * Shops.Payment.CHIP);
	}

	private static @Nullable Item item(final String id) {
		Identifier key = Identifier.tryParse(id);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == Items.AIR ? null : item;
	}

	private static int count(final Inventory inv, final Item item) {
		long n = 0;
		for (ItemStack s : inv) {
			if (s.getItem() == item) {
				n += s.getCount();
			}
		}

		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	private static void take(final Inventory inv, final Item item, final int n) {
		int left = n;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() == item) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}

		inv.setChanged();
	}

	private static void give(final Inventory inv, final Item item, final int n) {
		int max = Math.max(1, item.getDefaultMaxStackSize());
		for (int left = n; left > 0; left -= max) {
			inv.placeItemBackInInventory(new ItemStack(item, Math.min(left, max)), Prediction.SERVER_ONLY);
		}
	}
}
