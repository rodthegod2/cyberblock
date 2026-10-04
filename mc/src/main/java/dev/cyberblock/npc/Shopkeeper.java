package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.device.Shops;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A vendor's screen (megaplan task 7-N1, section 1.16): the NPC's {@code shop} content (a hub figure's own shop, else the role's
 * {@code shop_by_tag} entry for the figure's vendor tag, else the role's {@code shop}): its stock rows to buy ({@code b<row>}) and its
 * sell rows to sell ({@code s<row>}: plain copies from the hotbar and backpack only, never worn armour, the off hand or an enchanted,
 * named or worn copy), all for Minecraft eddies, as the role's {@code buy} and {@code sell} services allow. Each vendor
 * counts its own sales per stock row against the row's limit until the shop's restock time is up. Server thread.
 */
final class Shopkeeper implements Visit.Service {
	/** Sales per vendor and row: {since ms, sold}. */
	private final Map<String, Map<Integer, long[]>> sales = new HashMap<>();

	@Override
	public List<String> tabs(final Visit v) {
		List<String> tabs = new ArrayList<>();
		if (v.role.offers("buy")) {
			tabs.add("buy");
		}

		if (v.role.offers("sell")) {
			tabs.add("sell");
		}

		return tabs;
	}

	private static Shops.@Nullable Shop shop(final Visit v) {
		return v.shop == null ? null : NpcContent.shops().get(v.shop);
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		Shops.Shop shop = shop(v);
		if (shop == null) {
			return List.of();
		}

		boolean buy = v.tab.equals("buy");
		int eddies = Eddies.shown(v.player);
		long now = System.currentTimeMillis();
		List<NpcWire.Row> rows = new ArrayList<>();
		List<Shops.Row> list = buy ? shop.stock() : shop.sell();
		for (int j = 0; j < list.size() && rows.size() < NpcWire.MAX_ROWS; j++) {
			Shops.Row r = list.get(j);
			if (buy) {
				int left = this.left(v.key, j, r, shop, now);
				rows.add(new NpcWire.Row("b" + j, r.item(), r.count(), "", "", "", r.price(), left == 0 ? "sold_out" : "buy",
					left != 0 && (r.price() <= eddies || v.player.isCreative())));
			} else {
				Item item = item(r.item());
				boolean has = item != null && Eddies.goods(v.player.getInventory(), item, false) >= r.count();
				String note = item == null ? "" : Eddies.kept(v.player.getInventory(), item, r.count());
				rows.add(new NpcWire.Row("s" + j, r.item(), r.count(), "", "", note, r.price(), "sell", has));
			}
		}

		return rows;
	}

	/** How many more of a stock row this vendor sells now (-1: no limit). */
	private int left(final String vendor, final int row, final Shops.Row r, final Shops.Shop shop, final long now) {
		if (r.limit() == 0) {
			return -1;
		}

		long[] s = this.sales.computeIfAbsent(vendor, k -> new HashMap<>()).get(row);
		if (s == null || shop.restockSeconds() > 0 && now - s[0] >= shop.restockSeconds() * 1000L) {
			return r.limit();
		}

		return (int) Math.max(0, r.limit() - s[1]);
	}

	private void sold(final String vendor, final int row, final Shops.Shop shop, final long now) {
		Map<Integer, long[]> rows = this.sales.computeIfAbsent(vendor, k -> new HashMap<>());
		long[] s = rows.get(row);
		if (s == null || shop.restockSeconds() > 0 && now - s[0] >= shop.restockSeconds() * 1000L) {
			s = new long[] {now, 0};
			rows.put(row, s);
		}

		s[1]++;
	}

	/** {@code b12} → 12 (kind 'b'); -1 for anything else. */
	static int cell(final String row, final char kind) {
		return row.length() >= 2 && row.length() <= 3 && row.charAt(0) == kind && row.substring(1).matches("\\d{1,2}") ? Integer.parseInt(row.substring(1)) : -1;
	}

	@Override
	public String press(final Visit v, final String row) {
		boolean buy = row.startsWith("b");
		Shops.Shop shop = shop(v);
		int j = cell(row, buy ? 'b' : 's');
		List<Shops.Row> list = shop == null ? List.of() : buy ? shop.stock() : shop.sell();
		if (j < 0 || j >= list.size() || !v.role.offers(buy ? "buy" : "sell")) {
			return "gone";
		}

		Shops.Row r = list.get(j);
		Item item = item(r.item());
		if (item == null) {
			return "gone";
		}

		String name = new ItemStack(item).getHoverName().getString();
		if (!buy) {
			if (Eddies.goods(v.player.getInventory(), item, false) < r.count()) {
				return Eddies.kept(v.player.getInventory(), item, r.count()).isEmpty() ? "noitem" : "kept";
			}

			Eddies.takeGoods(v.player.getInventory(), item, r.count());
			Eddies.earn(v.player, r.price());
			Cyberblock.LOG.info("npc: {} sold {} x{} for {} eddies to {}", v.player.getPlainTextName(), r.item(), r.count(), r.price(), v.shop);
			v.say("sale", "item", name, "price", Integer.toString(r.price()));
			return "sold";
		}

		long now = System.currentTimeMillis();
		if (this.left(v.key, j, r, shop, now) == 0) {
			return "stock";
		}

		if (!Eddies.pay(v.player, r.price())) {
			v.say("no_funds", "item", name, "price", Integer.toString(r.price()));
			return "poor";
		}

		Eddies.give(v.player, item, r.count());
		this.sold(v.key, j, shop, now);
		Cyberblock.LOG.info("npc: {} bought {} x{} for {} eddies from {}", v.player.getPlainTextName(), r.item(), r.count(), r.price(), v.shop);
		v.say("purchase", "item", name, "price", Integer.toString(r.price()));
		return "ok";
	}

	static @Nullable Item item(final String id) {
		Identifier key = Identifier.tryParse(id);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == Items.AIR ? null : item;
	}

	void clear() {
		this.sales.clear();
	}
}
