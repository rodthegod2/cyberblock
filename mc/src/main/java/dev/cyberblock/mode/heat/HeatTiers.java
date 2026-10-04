package dev.cyberblock.mode.heat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Server Heat tiers as data (megaplan section 1.7, kind {@code heat_tier}; 8A owns the defaults and the schema,
 * {@code shared/content-schemas/heat_tier.schema.json}): one file per tier,
 * <pre>
 * {"schema":1, "tier":1-5, "name":"..." (48 bytes), "description":"..." (200 bytes), "enter":1-100, "exit":0-99 (below enter),
 *  "decay_per_s":0-5, "grace_s":0-120, "host_wanted":0-5, "top_chaos_wanted"?:0-5,
 *  "response":{"target":"area"|"loudest"|"top_chaos"|"everyone", "max_alive":1-24,
 *              "waves":[{"entry":"<spawn_palette id>", "every_s":5-300}] (1-4)}}
 * </pre>
 * Whole numbers where the schema says integer; no other keys (the schema's {@code additionalProperties:false}; an {@code id} is
 * allowed). Without usable content the built-in tiers ({@link HeatRules#BUILT_IN}, the same numbers as 8A's defaults) apply;
 * {@link #source} says which. Server only.
 */
public final class HeatTiers {
	private static final Set<String> KEYS = Set.of("schema", "id", "tier", "name", "description", "enter", "exit", "decay_per_s", "grace_s", "host_wanted",
		"top_chaos_wanted", "response");
	private static final Set<String> RESPONSE_KEYS = Set.of("target", "max_alive", "waves");
	private static final Set<String> WAVE_KEYS = Set.of("entry", "every_s");
	private static final Pattern ENTRY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	private static @Nullable ContentKind<HeatTier> kind;

	private HeatTiers() {
	}

	/** Common init, once, before the server loads its datapacks. */
	static void register() {
		kind = ContentKind.register("heat_tier", HeatTiers::parse, false);
	}

	static HeatTier parse(final Identifier id, final JsonObject json) {
		only(json, KEYS, "");
		int tier = (int) number(json, "tier", 1, 5, true);
		String name = text(json, "name", 48);
		text(json, "description", 200);
		double enter = number(json, "enter", 1, 100, true), exit = number(json, "exit", 0, 99, true);
		if (exit >= enter) {
			throw new IllegalArgumentException("exit must be below enter");
		}

		double decay = number(json, "decay_per_s", 0, 5, false);
		int grace = (int) number(json, "grace_s", 0, 120, true);
		int hostWanted = (int) number(json, "host_wanted", 0, 5, true);
		int topWanted = json.has("top_chaos_wanted") ? (int) number(json, "top_chaos_wanted", 0, 5, true) : -1;
		JsonObject r = object(json, "response");
		only(r, RESPONSE_KEYS, "response.");
		JsonElement target = r.get("target");
		if (target == null || !target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("response.target must be area, loudest, top_chaos or everyone");
		}

		int maxAlive = (int) number(r, "max_alive", 1, 24, true);
		JsonElement w = r.get("waves");
		if (w == null || !w.isJsonArray() || w.getAsJsonArray().isEmpty() || w.getAsJsonArray().size() > 4) {
			throw new IllegalArgumentException("response.waves must be a list of 1 to 4 waves");
		}

		List<HeatTier.Wave> waves = new ArrayList<>();
		for (JsonElement e : w.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				throw new IllegalArgumentException("each wave must be an object");
			}

			JsonObject o = e.getAsJsonObject();
			only(o, WAVE_KEYS, "response.waves[].");
			String entry = text(o, "entry", 128);
			if (!ENTRY.matcher(entry).matches()) {
				throw new IllegalArgumentException("response.waves[].entry must be a spawn_palette id (namespace:path)");
			}

			waves.add(new HeatTier.Wave(entry, (int) number(o, "every_s", 5, 300, true)));
		}

		return new HeatTier(tier, name, enter, exit, decay, grace, hostWanted, topWanted,
			new HeatTier.Response(HeatTier.Target.of(target.getAsString()), maxAlive, waves));
	}

	private static void only(final JsonObject o, final Set<String> keys, final String prefix) {
		for (String k : o.keySet()) {
			if (!keys.contains(k)) {
				throw new IllegalArgumentException("unknown key " + prefix + k);
			}
		}
	}

	private static JsonObject object(final JsonObject o, final String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException(key + " must be an object");
		}

		return e.getAsJsonObject();
	}

	private static String text(final JsonObject o, final String key, final int maxBytes) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isEmpty()
			|| e.getAsString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			throw new IllegalArgumentException(key + " must be text of 1 to " + maxBytes + " bytes");
		}

		return e.getAsString();
	}

	private static double number(final JsonObject o, final String key, final double min, final double max, final boolean whole) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be a number");
		}

		double v = e.getAsDouble();
		if (!(v >= min && v <= max) || whole && v != Math.rint(v)) {
			throw new IllegalArgumentException(key + " must be " + (whole ? "a whole number " : "") + "in " + fmt(min) + ".." + fmt(max));
		}

		return v;
	}

	private static String fmt(final double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}

	/**
	 * The tiers in force, by tier number: the loaded files (one per tier number, the first id wins; {@code enter} must rise with the
	 * tier number), or the built-ins when there are none or they are inconsistent.
	 */
	static List<HeatTier> active() {
		return choose(kind == null ? List.of() : Content.all(kind).values());
	}

	/** Pure: the content's tiers when usable, else the built-ins. */
	static List<HeatTier> choose(final Collection<HeatTier> loaded) {
		List<HeatTier> out = new ArrayList<>();
		for (HeatTier t : loaded) {
			if (out.stream().noneMatch(o -> o.tier() == t.tier())) {
				out.add(t);
			}
		}

		out.sort(Comparator.comparingInt(HeatTier::tier));
		for (int i = 1; i < out.size(); i++) {
			if (out.get(i).enter() <= out.get(i - 1).enter()) {
				return HeatRules.BUILT_IN;
			}
		}

		return out.isEmpty() ? HeatRules.BUILT_IN : List.copyOf(out);
	}

	/** "content (N tiers)" or "built-in tiers" for the start-up line. */
	static String source() {
		List<HeatTier> a = active();
		return a == HeatRules.BUILT_IN ? "built-in tiers" : "heat_tier content (" + a.size() + " tiers)";
	}
}
