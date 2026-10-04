package dev.cyberblock.device.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The vending machine's messages (megaplan 4-B1 with 4-M1; section 1.3 {@code cyberblock:ui} and {@code cyberblock:act}). The server
 * decides everything: it sends the shop, the client only shows it and asks to buy one line.
 *
 * <pre>
 * cyberblock:ui  t:"shop"   {"pos":[x,y,z], "title":"", "eddies":120, "result":"",
 *                            "lines":[{"id":"nicola", "item":"minecraft:honey_bottle", "n":1, "price":15, "stock":-1}]}
 * cyberblock:act t:"devact" {"pos":[x,y,z], "act":"buy", "line":"nicola"}
 * </pre>
 * {@code title} ≤ 64 chars ({@code ""}: the screen's own "Vending machine"); {@code eddies} the player's Minecraft eddies (section
 * 1.16) as the server counts them; at most {@link #MAX_LINES} lines; a line's {@code id} ≤ 32 chars, {@code item} an item id, {@code n}
 * 1..64, {@code price} 0..{@link #MAX_PRICE} eddies, {@code stock} -1 (not known) or 0..{@link #MAX_STOCK}; {@code result} the answer
 * to the last purchase: {@code ""}, {@code ok}, {@code poor} (not enough eddies), {@code stock} (sold out), {@code full} (no room),
 * {@code far}, {@code gone} (the machine left), {@code refused} (the host's toggles), shown with {@code cyberblock.shop.result.<code>}.
 * After a purchase the server sends the shop again with the new counts and the result.
 */
public final class ShopWire {
	public static final String T_SHOP = "shop";
	public static final int MAX_LINES = 27;
	public static final int TITLE_CAP = 64;
	public static final int ID_CAP = 32;
	public static final int MAX_PRICE = 1_000_000;
	public static final int MAX_STOCK = 9999;
	private static final Pattern RESULT = Pattern.compile("[a-z_]{0,16}");
	private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

	/** One thing the machine sells. */
	public record Line(String id, String item, int n, int price, int stock) {
	}

	/** One machine's shop, as one player sees it. */
	public record Shop(int x, int y, int z, String title, int eddies, List<Line> lines, String result) {
	}

	private ShopWire() {
	}

	/** The {@code shop} json the server sends (4M). */
	public static String shop(final Shop shop) {
		JsonObject m = new JsonObject();
		m.add("pos", pos(shop.x(), shop.y(), shop.z()));
		m.addProperty("title", shop.title());
		m.addProperty("eddies", shop.eddies());
		m.addProperty("result", shop.result());
		JsonArray lines = new JsonArray();
		for (Line line : shop.lines()) {
			JsonObject l = new JsonObject();
			l.addProperty("id", line.id());
			l.addProperty("item", line.item());
			l.addProperty("n", line.n());
			l.addProperty("price", line.price());
			l.addProperty("stock", line.stock());
			lines.add(l);
		}

		m.add("lines", lines);
		return m.toString();
	}

	/** A {@code shop} json, or null when anything is missing or out of its limits. */
	public static @Nullable Shop parseShop(final JsonObject m) {
		try {
			int[] p = pos(m.get("pos"));
			String title = m.has("title") ? m.get("title").getAsString() : "";
			String result = m.has("result") ? m.get("result").getAsString() : "";
			int eddies = m.get("eddies").getAsInt();
			JsonArray lines = m.getAsJsonArray("lines");
			if (p == null || title.length() > TITLE_CAP || !RESULT.matcher(result).matches() || eddies < 0 || lines.size() > MAX_LINES) {
				return null;
			}

			List<Line> out = new ArrayList<>();
			for (JsonElement e : lines) {
				JsonObject l = e.getAsJsonObject();
				Line line = new Line(l.get("id").getAsString(), l.get("item").getAsString(), l.get("n").getAsInt(), l.get("price").getAsInt(),
					l.has("stock") ? l.get("stock").getAsInt() : -1);
				if (line.id().isEmpty() || line.id().length() > ID_CAP || !ITEM.matcher(line.item()).matches() || line.n() < 1 || line.n() > 64
					|| line.price() < 0 || line.price() > MAX_PRICE || line.stock() < -1 || line.stock() > MAX_STOCK) {
					return null;
				}

				out.add(line);
			}

			return new Shop(p[0], p[1], p[2], title, eddies, List.copyOf(out), result);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The client's {@code devact buy} json. */
	public static String buy(final int x, final int y, final int z, final String line) {
		JsonObject m = new JsonObject();
		m.add("pos", pos(x, y, z));
		m.addProperty("act", "buy");
		m.addProperty("line", line);
		return m.toString();
	}

	/** The line a {@code devact buy} asks for (4M), or null when it has none or a bad one. */
	public static @Nullable String buyLine(final JsonObject m) {
		JsonElement line = m.get("line");
		if (line == null || !line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) {
			return null;
		}

		String id = line.getAsString();
		return id.isEmpty() || id.length() > ID_CAP ? null : id;
	}

	private static JsonArray pos(final int x, final int y, final int z) {
		JsonArray a = new JsonArray();
		a.add(x);
		a.add(y);
		a.add(z);
		return a;
	}

	private static int @Nullable [] pos(final @Nullable JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			return null;
		}

		JsonArray a = e.getAsJsonArray();
		return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
	}
}
