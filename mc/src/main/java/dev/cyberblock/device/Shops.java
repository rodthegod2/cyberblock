package dev.cyberblock.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The vending machines' Minecraft shops (megaplan 4-M1, content kind {@code shop} of section 1.7, 4A's schema
 * shared/content-schemas/shop.schema.json): {@code {"schema":1, "title_key", "stock":[{"item", "count", "price", "limit"?}], "sell"?:
 * [...], "restock"?: seconds}}. Prices are Minecraft eddies (section 1.16). A machine picks one of its device_map shops, the same one
 * every time for the same device id; each machine counts its own purchases per row against the row's limit until its restock time
 * is up. {@code sell} rows belong to Phase 7's hub vendors and are read but not offered here. Server thread only.
 */
public final class Shops {
	public record Row(String item, int count, int price, int limit) {
	}

	/** sell: what a hub vendor buys from players (megaplan 7-N1; price per row's count); vending machines ignore it. */
	public record Shop(String titleKey, List<Row> stock, int restockSeconds, List<Row> sell) {
		public Shop(final String titleKey, final List<Row> stock, final int restockSeconds) {
			this(titleKey, stock, restockSeconds, List.of());
		}
	}

	static final int MAX_ROWS = 24, MAX_COUNT = 64, MAX_PRICE = 99_999, MAX_LIMIT = 64, MAX_RESTOCK = 86_400;
	private static @Nullable ContentKind<Shop> kind;
	private static Map<Identifier, Shop> testShops = Map.of();

	/** One machine's purchases per row since its last restock (ms). */
	static final class Sales {
		long since;
		final int[] sold;

		Sales(final long since, final int rows) {
			this.since = since;
			this.sold = new int[rows];
		}
	}

	private final Map<String, Sales> sales = new HashMap<>();

	/** Common init, once (before the server loads its datapacks). Server only: a client gets one shop at a time with cyberblock:ui. */
	static void register() {
		kind = ContentKind.register("shop", Shops::parse, false);
	}

	static Shop parse(final Identifier id, final JsonObject json) {
		String title = Content.string(json, "title_key", 96);
		if (!title.matches("[a-z0-9_.]+")) {
			throw new IllegalArgumentException("title_key must be a lang key (a-z, 0-9, _ and .)");
		}

		List<Row> stock = rows(json.get("stock"), true);
		List<Row> sell = json.has("sell") ? rows(json.get("sell"), false) : List.of();
		int restock = 0;
		if (json.has("restock")) {
			restock = whole(json.get("restock"), 0, MAX_RESTOCK, "restock must be 0 to " + MAX_RESTOCK + " seconds");
		}

		return new Shop(title, stock, restock, sell);
	}

	private static List<Row> rows(final @Nullable JsonElement e, final boolean stock) {
		if (!(e instanceof JsonArray a) || a.size() > MAX_ROWS || stock && a.isEmpty()) {
			throw new IllegalArgumentException((stock ? "stock" : "sell") + " must be an array of 1 to " + MAX_ROWS + " rows");
		}

		List<Row> out = new ArrayList<>();
		for (JsonElement r : a) {
			if (!(r instanceof JsonObject o)) {
				throw new IllegalArgumentException("a row is {item, count, price}");
			}

			String item = Content.string(o, "item", 128);
			if (Identifier.tryParse(item) == null || !item.contains(":")) {
				throw new IllegalArgumentException("item must be an id like minecraft:bread");
			}

			int count = whole(o.get("count"), 1, MAX_COUNT, "count must be 1 to " + MAX_COUNT);
			int price = whole(o.get("price"), 1, MAX_PRICE, "price must be 1 to " + MAX_PRICE + " eddies");
			int limit = o.has("limit") ? whole(o.get("limit"), 1, MAX_LIMIT, "limit must be 1 to " + MAX_LIMIT) : 0;
			out.add(new Row(item, count, price, limit));
		}

		return List.copyOf(out);
	}

	private static int whole(final @Nullable JsonElement e, final int min, final int max, final String why) {
		double v = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(why);
		}

		return (int) v;
	}

	/** Tests (before register): these shops instead of the loaded content. */
	static void use(final Map<Identifier, Shop> shops) {
		testShops = shops;
	}

	private static Map<Identifier, Shop> all() {
		return kind == null ? testShops : Content.all(kind);
	}

	/** The id of the shop this machine sells from: one of its device_map shops that exists, the same one for the same device id. */
	static @Nullable Identifier pick(final List<String> shops, final long deviceId) {
		List<Identifier> known = new ArrayList<>();
		for (String s : shops) {
			Identifier id = Identifier.tryParse(s);
			if (id != null && all().containsKey(id)) {
				known.add(id);
			}
		}

		return known.isEmpty() ? null : known.get(Math.floorMod(Long.hashCode(deviceId), known.size()));
	}

	static @Nullable Shop get(final Identifier id) {
		return all().get(id);
	}

	/** How many more of a row this machine sells before its restock (Integer.MAX_VALUE: no limit). machine: owner and device id. */
	int left(final String machine, final Shop shop, final int row, final long nowMs) {
		Row r = shop.stock().get(row);
		return r.limit() == 0 ? Integer.MAX_VALUE : Math.max(0, r.limit() - this.sales(machine, shop, nowMs).sold[row]);
	}

	/** Counts n purchases of a row (the caller checked left()). */
	void sold(final String machine, final Shop shop, final int row, final int n, final long nowMs) {
		this.sales(machine, shop, nowMs).sold[row] += n;
	}

	private Sales sales(final String machine, final Shop shop, final long nowMs) {
		Sales s = this.sales.get(machine);
		boolean restocked = s != null && shop.restockSeconds() > 0 && nowMs - s.since >= shop.restockSeconds() * 1000L;
		if (s == null || s.sold.length != shop.stock().size() || restocked) {
			s = new Sales(nowMs, shop.stock().size());
			this.sales.put(machine, s);
		}

		return s;
	}

	/** Server stop: every machine is full again next time. */
	void clear() {
		this.sales.clear();
	}

	/**
	 * Paying a price from eddies and eddie chips (a chip is worth 100): eddies first, then as few chips as cover the rest, the change
	 * back in eddies. Null when the player cannot pay.
	 */
	public record Payment(int eddies, int chips, int change) {
		public static final int CHIP = 100;

		public static @Nullable Payment of(final int haveEddies, final int haveChips, final int price) {
			if (price <= 0 || (long) haveEddies + (long) haveChips * CHIP < price) {
				return null;
			}

			int eddies = Math.min(haveEddies, price);
			int rest = price - eddies;
			int chips = (rest + CHIP - 1) / CHIP;
			return new Payment(eddies, chips, chips * CHIP - rest);
		}
	}
}
