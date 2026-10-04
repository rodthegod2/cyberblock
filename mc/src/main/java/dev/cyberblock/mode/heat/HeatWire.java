package dev.cyberblock.mode.heat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Server Heat on the wire (megaplan 8-T1; 8P documents the rows):
 * <ul>
 * <li>DOWN to a host's DLL (section 1.9): {@code {"t":"heat","tier":0..5}}: the real wanted level the scripts set for that host (the
 * server's tier mapped by {@link HeatRules#wanted}).</li>
 * <li>{@code cyberblock:ui t:"heat"} to every player (the HUD meter), on change and at most once a second:
 * {@code {"v":0..100, "tier":0..5, "top":"<name of the top chaos player>"|"", "hunted":bool}}; {@code hunted}: Minecraft squads are
 * after this player.</li>
 * </ul>
 */
public final class HeatWire {
	public static final String T = "heat";
	static final int NAME_CAP = 64;

	/** The meter as a client sees it. */
	public record Meter(int value, int tier, String top, boolean hunted) {
		public static final Meter NONE = new Meter(0, 0, "", false);
	}

	private HeatWire() {
	}

	public static String down(final int wanted) {
		return "{\"t\":\"heat\",\"tier\":" + Math.clamp(wanted, 0, 5) + "}";
	}

	public static String ui(final Meter m) {
		JsonObject o = new JsonObject();
		o.addProperty("v", Math.clamp(m.value(), 0, 100));
		o.addProperty("tier", Math.clamp(m.tier(), 0, 5));
		o.addProperty("top", cap(m.top()));
		o.addProperty("hunted", m.hunted());
		return o.toString();
	}

	/** The client's parse; null for anything malformed (dropped). */
	public static @Nullable Meter parse(final String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			int v = o.get("v").getAsInt(), tier = o.get("tier").getAsInt();
			String top = o.has("top") ? o.get("top").getAsString() : "";
			if (v < 0 || v > 100 || tier < 0 || tier > 5 || top.getBytes(StandardCharsets.UTF_8).length > NAME_CAP) {
				return null;
			}

			return new Meter(v, tier, top, o.has("hunted") && o.get("hunted").getAsBoolean());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String cap(final String s) {
		return s.getBytes(StandardCharsets.UTF_8).length <= NAME_CAP ? s : s.substring(0, NAME_CAP / 4);
	}
}
