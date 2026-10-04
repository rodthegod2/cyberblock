package dev.cyberblock.mode.heat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Server Heat tiers as data (megaplan section 1.7, kind {@code heat_tier}; 8A ships the defaults and the schema): one file per tier,
 * <pre>
 * {"schema":1, "tier":1-5, "threshold":1-100, "decay":0-10, "wanted":0-5,
 *  "response":{"patrol":0-8, "chase":0-8, "officers":0-8, "heavy":0-4, "drones":0-4, "maxtac":0-8, "everyone":bool}}
 * </pre>
 * {@code threshold}: the meter value the tier starts at; {@code decay}: meter points lost per second at this tier (after 10 s of
 * quiet); {@code wanted}: the real wanted level hosts get (Phase 0 e); {@code response}: the Minecraft squads (every key optional, 0 or
 * false when absent; see {@link HeatTier.Response}). Unknown keys are ignored (an {@code id}, a {@code name} or notes are fine).
 * Without usable content the built-in tiers ({@link HeatRules#BUILT_IN}) apply; {@link #source} says which. Server only.
 */
public final class HeatTiers {
	private static @Nullable ContentKind<HeatTier> kind;

	private HeatTiers() {
	}

	/** Common init, once, before the server loads its datapacks. */
	static void register() {
		kind = ContentKind.register("heat_tier", HeatTiers::parse, false);
	}

	static HeatTier parse(final Identifier id, final JsonObject json) {
		int tier = (int) number(json, "tier", 1, 5, true);
		double threshold = number(json, "threshold", 1, 100, false);
		double decay = number(json, "decay", 0, 10, false);
		int wanted = (int) number(json, "wanted", 0, 5, true);
		HeatTier.Response response = HeatTier.Response.NONE;
		JsonElement r = json.get("response");
		if (r != null) {
			if (!r.isJsonObject()) {
				throw new IllegalArgumentException("response must be an object");
			}

			JsonObject o = r.getAsJsonObject();
			response = new HeatTier.Response(count(o, "patrol", 8), count(o, "chase", 8), count(o, "officers", 8), count(o, "heavy", 4),
				count(o, "drones", 4), count(o, "maxtac", 8), flag(o, "everyone"));
		}

		return new HeatTier(tier, threshold, decay, wanted, response);
	}

	private static int count(final JsonObject o, final String key, final int max) {
		return o.has(key) ? (int) number(o, key, 0, max, true) : 0;
	}

	private static boolean flag(final JsonObject o, final String key) {
		JsonElement e = o.get(key);
		if (e == null) {
			return false;
		}

		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be true or false");
		}

		return e.getAsBoolean();
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
	 * The tiers in force, sorted by threshold: the loaded files (one per tier number, the first id wins; tier numbers must rise with the
	 * thresholds), or the built-ins when there are none or they are inconsistent.
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
			if (out.get(i).threshold() <= out.get(i - 1).threshold()) {
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
