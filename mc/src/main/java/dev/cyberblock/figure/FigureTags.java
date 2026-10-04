package dev.cyberblock.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The Phase 5 tags of one {"t":"npcs"} entry (megaplan section 1.9 and its 2026-10-04 amendment): {@code role} (vendor type or ""),
 * {@code fac} (affiliation enum name), {@code g} (0 unknown, 1 male, 2 female), {@code arch} (archetype), {@code anim} bits (1 walk,
 * 2 run, 4 aim, 8 fire, 16 ragdoll, 32 dead, 64 crouch, 128 combat), {@code vc} ("&lt;type&gt;/&lt;model&gt;"), {@code col}
 * ("#RRGGBB", only when a colour template matched) and {@code hp} (0..1). Every field is optional: a host without them (an older DLL,
 * a field Phase 0 could not read) gets the written fallbacks of check (f). A bad field reads as absent and never costs the entry,
 * which ProxyDiff already validated. Pure.
 */
public record FigureTags(String role, String fac, int g, String arch, int anim, String vc, int col, float hp) {
	public static final int WALK = 1, RUN = 2, AIM = 4, FIRE = 8, RAGDOLL = 16, DEAD = 32, CROUCH = 64, COMBAT = 128;
	/** No tags at all: the fallback figure (neutral skin, motion-based walk and run, the class default colour, full health). */
	public static final FigureTags NONE = new FigureTags("", "", 0, "", 0, "", -1, 1.0F);
	/** The tag names in the order the log lists them. */
	public static final String[] FIELDS = {"role", "fac", "g", "arch", "anim", "vc", "col", "hp"};
	/** Text tags are short enum names; the amendment caps vc at 32 chars. */
	static final int MAX_TEXT = 32;
	private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
	private static final Pattern DECIMAL_ID = Pattern.compile("[0-9]{1,20}");

	/** The tags per entity id of a message, and which tag names any entry carried (valid or not). */
	public record Parsed(Map<Long, FigureTags> byId, Set<String> seen) {
	}

	/** Each entry's tags by id (entries with no usable id are skipped; ProxyDiff decides which entries count). */
	public static Parsed parse(final JsonObject message) {
		Map<Long, FigureTags> byId = new HashMap<>();
		Set<String> seen = new TreeSet<>();
		if (!(message.get("list") instanceof JsonArray list)) {
			return new Parsed(byId, seen);
		}

		for (JsonElement e : list) {
			if (!(e instanceof JsonObject o) || !(o.get("id") instanceof JsonPrimitive id) || !id.isString()
				|| !DECIMAL_ID.matcher(id.getAsString()).matches()) {
				continue;
			}

			for (String field : FIELDS) {
				if (o.has(field)) {
					seen.add(field);
				}
			}

			try {
				byId.putIfAbsent(Long.parseUnsignedLong(id.getAsString()), of(o));
			} catch (NumberFormatException tooBig) {
				// more than 2^64 - 1: ProxyDiff skips the entry too
			}
		}

		return new Parsed(byId, seen);
	}

	/** One entry's tags. */
	public static FigureTags of(final JsonObject o) {
		double g = number(o.get("g")), anim = number(o.get("anim")), hp = number(o.get("hp"));
		String col = text(o.get("col"));
		return new FigureTags(text(o.get("role")), text(o.get("fac")), g == 1.0 || g == 2.0 ? (int) g : 0, text(o.get("arch")),
			anim >= 0.0 && anim <= 255.0 && anim == Math.rint(anim) ? (int) anim : 0, text(o.get("vc")),
			COLOUR.matcher(col).matches() ? Integer.parseInt(col.substring(1), 16) : -1, hp >= 0.0 && hp <= 1.0 ? (float) hp : 1.0F);
	}

	public boolean has(final int bit) {
		return (this.anim & bit) != 0;
	}

	/** A short string, "" when missing, not a string or too long. */
	private static String text(final JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			return "";
		}

		String s = p.getAsString();
		return s.getBytes(StandardCharsets.UTF_8).length <= MAX_TEXT ? s : "";
	}

	private static double number(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
	}
}
