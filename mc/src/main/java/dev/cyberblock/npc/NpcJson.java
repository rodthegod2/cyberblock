package dev.cyberblock.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** The field shapes the Phase 7 content parsers share (megaplan section 1.7: byte caps, whole numbers in a range, ids). Pure. */
final class NpcJson {
	private NpcJson() {
	}

	/** A whole number in [min, max], or def when the key is absent. */
	static int whole(final JsonObject json, final String key, final int min, final int max, final int def) {
		if (!json.has(key)) {
			return def;
		}

		JsonElement e = json.get(key);
		double v = e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max);
		}

		return (int) v;
	}

	/** A number in [min, max], or def when the key is absent. */
	static double number(final JsonObject json, final String key, final double min, final double max, final double def) {
		if (!json.has(key)) {
			return def;
		}

		JsonElement e = json.get(key);
		double v = e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
		if (!(v >= min && v <= max)) {
			throw new IllegalArgumentException(key + " must be a number from " + min + " to " + max);
		}

		return v;
	}

	/** An optional string of at most maxBytes, def when absent. */
	static String string(final JsonObject json, final String key, final int maxBytes, final String def) {
		return json.has(key) ? Content.string(json, key, maxBytes) : def;
	}

	/** An id like "minecraft:bread" (the namespace is required). */
	static Identifier id(final String text, final String what) {
		Identifier id = text.contains(":") ? Identifier.tryParse(text) : null;
		if (id == null) {
			throw new IllegalArgumentException(what + " must be an id like cyberblock:name, not " + text);
		}

		return id;
	}

	/** An optional array of at most max elements (empty when absent). */
	static JsonArray array(final JsonObject json, final String key, final int max) {
		if (!json.has(key)) {
			return new JsonArray();
		}

		if (!(json.get(key) instanceof JsonArray a) || a.size() > max) {
			throw new IllegalArgumentException(key + " must be an array of at most " + max);
		}

		return a;
	}

	/** An optional array of strings, each 1..maxBytes bytes. */
	static List<String> strings(final JsonObject json, final String key, final int max, final int maxBytes) {
		List<String> out = new ArrayList<>();
		for (JsonElement e : array(json, key, max)) {
			JsonObject wrap = new JsonObject();
			wrap.add("v", e);
			String s = Content.string(wrap, "v", maxBytes);
			if (s.isBlank()) {
				throw new IllegalArgumentException(key + " holds an empty string");
			}

			out.add(s);
		}

		return List.copyOf(out);
	}

	/** An optional array of ids. */
	static List<Identifier> ids(final JsonObject json, final String key, final int max) {
		return strings(json, key, max, 128).stream().map(s -> id(s, key)).toList();
	}

	/** An object element of an array, or a reason. */
	static JsonObject object(final @Nullable JsonElement e, final String what) {
		if (!(e instanceof JsonObject o)) {
			throw new IllegalArgumentException(what + " must be an object");
		}

		return o;
	}

	/** Lower case letters and digits only: "TygerClaws", "tyger_claws" and "Tyger Claws" are one faction. */
	static String key(final String s) {
		StringBuilder b = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				b.append(Character.toLowerCase(c));
			}
		}

		return b.toString();
	}
}
