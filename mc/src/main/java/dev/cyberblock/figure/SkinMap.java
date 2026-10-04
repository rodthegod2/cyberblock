package dev.cyberblock.figure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The {@code skin_map} content kind (megaplan sections 1.7 and 1.12, tasks 5-A2 and 5-F1): which NPC skin, on which model, a Night
 * City person wears. One row per file, as 5A's art/gen_npc_skins.py writes them:
 * <pre>{"schema":1, "texture":"cyberblock:textures/entity/npc/tyger_claws_f.png", "model":"slim", "faction":"TygerClaws",
 * "gender":"female", "role":..., "archetype":..., "priority":0, "cyber":...}</pre>
 * A person matches a row when each of the row's {@code role}, {@code faction}, {@code gender} and {@code archetype} keys (a value or a
 * list of values) holds for its {@code role}, {@code fac}, {@code g} and {@code arch} tags; values match without case, spaces or
 * punctuation ("Tyger Claws" = "TygerClaws"); an unknown gender matches no gender row. Of the matching rows the highest
 * {@code priority} wins, then the row with more match keys, then, among rows still tied, one picked by the NPC id (stable, so a crowd
 * wears a mix). Rows with {@code cyber} are the linked players' cyber skins (5K) and never match an NPC. {@code model} is classic or
 * slim; without it a woman is slim and anyone else classic. With no matching row a person wears the neutral figure skin. Pure apart
 * from the content snapshot.
 */
public final class SkinMap {
	/** The neutral figure skin (3A's textures/entity/figure/neutral.png): nothing matched. */
	public static final String NEUTRAL = "neutral";
	private static final int MAX_TEXTURE = 128;

	/** One row: conditions normalised (key); genders 1 male, 2 female; slim null means "by gender". */
	public record Row(String texture, @Nullable Boolean slim, Set<String> roles, Set<String> factions, Set<String> archetypes,
		Set<Integer> genders, int priority, boolean cyber) {
		/** -1 when the row does not match, else how many match keys it has. */
		int keys(final FigureTags tags) {
			if (this.cyber || !matches(this.roles, tags.role()) || !matches(this.factions, tags.fac()) || !matches(this.archetypes, tags.arch())
				|| !this.genders.isEmpty() && !this.genders.contains(tags.g())) {
				return -1;
			}

			return (this.roles.isEmpty() ? 0 : 1) + (this.factions.isEmpty() ? 0 : 1) + (this.archetypes.isEmpty() ? 0 : 1) + (this.genders.isEmpty() ? 0 : 1);
		}

		private static boolean matches(final Set<String> condition, final String tag) {
			return condition.isEmpty() || condition.contains(key(tag));
		}
	}

	/** What a person wears: the skin texture (an Identifier, or NEUTRAL) and whether the model is slim. */
	public record Skin(String texture, boolean slim) {
	}

	private SkinMap() {
	}

	/** The content parser: one file, one row (throws IllegalArgumentException with the reason). */
	public static Row parse(final Identifier id, final JsonObject json) {
		String texture = Content.string(json, "texture", MAX_TEXTURE);
		Identifier parsed = Identifier.tryParse(texture);
		if (parsed == null || !parsed.getPath().endsWith(".png")) {
			throw new IllegalArgumentException("texture must be a namespace:path/to/skin.png");
		}

		Boolean slim = null;
		if (json.has("model")) {
			slim = switch (Content.string(json, "model", 8)) {
				case "classic" -> false;
				case "slim" -> true;
				default -> throw new IllegalArgumentException("model must be classic or slim");
			};
		}

		Set<Integer> genders = new TreeSet<>();
		for (String g : strings(json, "gender")) {
			genders.add(switch (g) {
				case "male" -> 1;
				case "female" -> 2;
				default -> throw new IllegalArgumentException("gender must be male or female");
			});
		}

		int priority = 0;
		if (json.has("priority")) {
			JsonElement p = json.get("priority");
			if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber() || p.getAsDouble() != Math.rint(p.getAsDouble()) || Math.abs(p.getAsDouble()) > 1000) {
				throw new IllegalArgumentException("priority must be a whole number within -1000..1000");
			}

			priority = p.getAsInt();
		}

		return new Row(parsed.toString(), slim, keys(json, "role"), keys(json, "faction"), keys(json, "archetype"), genders, priority,
			json.has("cyber"));
	}

	/** The skin of a person with these tags and entity id among the rows: the best match, else the neutral skin. */
	public static Skin resolve(final Collection<Row> rows, final FigureTags tags, final long id) {
		List<Row> best = new ArrayList<>();
		int bestPriority = Integer.MIN_VALUE, bestKeys = -1;
		for (Row row : rows) {
			int keys = row.keys(tags);
			if (keys < 0) {
				continue;
			}

			if (row.priority() > bestPriority || row.priority() == bestPriority && keys > bestKeys) {
				best.clear();
				bestPriority = row.priority();
				bestKeys = keys;
			}

			if (row.priority() == bestPriority && keys == bestKeys) {
				best.add(row);
			}
		}

		if (best.isEmpty()) {
			return new Skin(NEUTRAL, false);
		}

		Row row = best.get((int) Long.remainderUnsigned(mix(id), best.size()));
		return new Skin(row.texture(), row.slim() != null ? row.slim() : tags.g() == 2);
	}

	/** Spreads ids over the tied rows (SplitMix64's finaliser): neighbouring ids do not all wear the same skin. */
	static long mix(final long id) {
		long z = id + 0x9E3779B97F4A7C15L;
		z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
		z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
		return z ^ z >>> 31;
	}

	/** A tag or condition without case, spaces or punctuation: "Tyger Claws", "tyger_claws" and "TygerClaws" are one. */
	public static String key(final String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
				out.append(c);
			}
		}

		return out.toString();
	}

	/** An optional value or list of values, normalised. */
	static Set<String> keys(final JsonObject json, final String field) {
		Set<String> out = new TreeSet<>();
		for (String s : strings(json, field)) {
			String key = key(s);
			if (key.isEmpty()) {
				throw new IllegalArgumentException(field + " needs letters or digits");
			}

			out.add(key);
		}

		return out;
	}

	/** An optional string or list of 1..32 strings, as given. */
	static List<String> strings(final JsonObject json, final String field) {
		JsonElement e = json.get(field);
		if (e == null) {
			return List.of();
		}

		List<JsonElement> items = e.isJsonArray() ? e.getAsJsonArray().asList() : List.of(e);
		if (items.isEmpty() || items.size() > 32) {
			throw new IllegalArgumentException(field + " must hold 1..32 strings");
		}

		List<String> out = new ArrayList<>();
		for (JsonElement item : items) {
			JsonObject one = new JsonObject();
			one.add(field, item);
			out.add(Content.string(one, field, FigureTags.MAX_TEXT));
		}

		return out;
	}
}
