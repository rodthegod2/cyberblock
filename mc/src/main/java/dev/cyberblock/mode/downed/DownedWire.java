package dev.cyberblock.mode.downed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Downed on the wire ({@code cyberblock:ui}, megaplan 8-T2; 8P documents the rows):
 * <ul>
 * <li>{@code downed} to the downed player, twice a second while down and once more when it ends: {@code {"down":bool, "left":s,
 * "trauma":s|-1, "revive":0..1, "by":"<reviver>"|"", "price":eddies, "solo":bool}}: bleed-out seconds left, the flyer's
 * countdown, a teammate's revive in progress, Trauma Team's price, and whether nobody else is around to revive.</li>
 * <li>{@code downlist} to every player, on change and on join: {@code {"list":["<uuid>", ...]}} (at most 200): who is down, so every
 * client draws them crawling.</li>
 * <li>{@code revive} to the reviver while it holds: {@code {"name":"<downed>", "p":0..1}}.</li>
 * </ul>
 */
public final class DownedWire {
	public static final String T_DOWNED = "downed", T_LIST = "downlist", T_REVIVE = "revive";
	static final int LIST_CAP = 200, NAME_CAP = 64;

	/** The downed player's own view. */
	public record Status(boolean down, int left, int trauma, float revive, String by, int price, boolean solo) {
		public static final Status UP = new Status(false, 0, -1, 0.0F, "", 0, false);
	}

	/** A reviver's progress bar. */
	public record Revive(String name, float p) {
	}

	private DownedWire() {
	}

	public static String status(final Status s) {
		JsonObject o = new JsonObject();
		o.addProperty("down", s.down());
		o.addProperty("left", Math.max(0, s.left()));
		o.addProperty("trauma", Math.max(-1, s.trauma()));
		o.addProperty("revive", Math.clamp(s.revive(), 0.0F, 1.0F));
		o.addProperty("by", cap(s.by()));
		o.addProperty("price", Math.max(0, s.price()));
		o.addProperty("solo", s.solo());
		return o.toString();
	}

	public static @Nullable Status parseStatus(final String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			int left = o.get("left").getAsInt(), trauma = o.get("trauma").getAsInt(), price = o.get("price").getAsInt();
			float revive = o.get("revive").getAsFloat();
			String by = o.get("by").getAsString();
			if (left < 0 || left > 3600 || trauma < -1 || trauma > 3600 || !(revive >= 0.0F && revive <= 1.0F) || price < 0 || bytes(by) > NAME_CAP) {
				return null;
			}

			return new Status(o.get("down").getAsBoolean(), left, trauma, revive, by, price, o.has("solo") && o.get("solo").getAsBoolean());
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String list(final Set<UUID> down) {
		JsonArray a = new JsonArray();
		down.stream().sorted().limit(LIST_CAP).forEach(u -> a.add(u.toString()));
		JsonObject o = new JsonObject();
		o.add("list", a);
		return o.toString();
	}

	public static @Nullable Set<UUID> parseList(final String json) {
		try {
			JsonArray a = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("list");
			if (a.size() > LIST_CAP) {
				return null;
			}

			Set<UUID> out = new HashSet<>();
			for (JsonElement e : a) {
				out.add(UUID.fromString(e.getAsString()));
			}

			return out;
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String revive(final Revive r) {
		JsonObject o = new JsonObject();
		o.addProperty("name", cap(r.name()));
		o.addProperty("p", Math.clamp(r.p(), 0.0F, 1.0F));
		return o.toString();
	}

	public static @Nullable Revive parseRevive(final String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String name = o.get("name").getAsString();
			float p = o.get("p").getAsFloat();
			return bytes(name) <= NAME_CAP && p >= 0.0F && p <= 1.0F ? new Revive(name, p) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static int bytes(final String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}

	private static String cap(final String s) {
		return bytes(s) <= NAME_CAP ? s : s.substring(0, NAME_CAP / 4);
	}
}
