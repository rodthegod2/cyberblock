package dev.cyberblock.mode.overlord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Overlord's two content kinds (megaplan section 1.7; defaults and JSON Schemas by 8A in
 * {@code data/cyberblock/cyberblock/{spawn_palette,event}/} and {@code shared/content-schemas/}): this class is their loader and
 * reads exactly 8A's shapes. Unknown fields are refused, like the schemas' {@code additionalProperties: false}.
 *
 * <p><b>spawn_palette</b>: {@code {"schema":1, "name" <= 48, "description" <= 200, "group": gang|law|machine|monster,
 * "kind": minecraft|night_city, "cost" 1..60, "count" 1..8, "hostile", "cooldown_s"? 0..300, "id"?}} plus, for {@code minecraft},
 * {@code "mob"} (an entity id) and, for {@code night_city}, {@code "rec"} (ganger, ncpd, maxtac, drone), {@code "faction"} (an
 * attitude group name), {@code "heavy"}? and {@code "squad": {"mob", "count" 1..8}} (the Minecraft stand-in). One entry is one spawn
 * action: a bigger spawn is a dearer entry.
 *
 * <p><b>event</b>: {@code {"schema":1, "name", "description", "cost" 1..100, "warning_s" 3..30, "cooldown_s" 0..900, "radius" 4..128,
 * "scales_with_intensity", "night_city", "effect": {"type", ...}, "id"?}} with the effect one of blackout {seconds 5..120}, emp
 * {seconds 3..60, cyberware_off}, airstrike {strikes 1..20, spread 2..32, power 1..4, interval_ticks 2..40}, traffic_jam
 * {seconds 10..300}, supply_drop {eddies 0..2000, items: [{item, count 1..64}] <= 8}, weather {weather: clear|rain|thunder,
 * seconds 30..600}, outbreak {spawn: a spawn_palette id, count 1..4}.
 */
public final class OverlordContent {
	public static final String SPAWN_KIND = "spawn_palette", EVENT_KIND = "event";
	public static final Set<String> GROUPS = Set.of("gang", "law", "machine", "monster");
	/** The 1.9 {@code spawn.rec} classes (Phase 0 amendment: no turret, a turret is a Minecraft-only entry). */
	public static final Set<String> RECS = Set.of("ganger", "ncpd", "maxtac", "drone");
	public static final Set<String> EFFECTS = Set.of("blackout", "emp", "airstrike", "traffic_jam", "supply_drop", "weather", "outbreak");
	public static final Set<String> WEATHERS = Set.of("clear", "rain", "thunder");

	/**
	 * One palette entry. For a Minecraft entry {@code squadMob}/{@code squadCount} are its {@code mob}/{@code count}; for a Night City
	 * entry they are its {@code squad}, the Minecraft stand-in used wherever the Night City spawn cannot happen.
	 */
	public record SpawnEntry(String name, String description, String group, boolean nightCity, int cost, int count, boolean hostile, int cooldownS,
		@Nullable String rec, String faction, boolean heavy, Identifier squadMob, int squadCount) {
	}

	public record Stack(Identifier item, int count) {
	}

	/** One event's effect; only the fields of its type are set (the others are 0, false, empty or null). */
	public record Effect(String type, int seconds, boolean cyberwareOff, int strikes, int spread, double power, int intervalTicks, int eddies,
		List<Stack> items, String weather, @Nullable Identifier spawn, int count) {
	}

	public record EventEntry(String name, String description, int cost, int warningS, int cooldownS, int radius, boolean scales, boolean nightCity,
		Effect effect) {
	}

	private static final Set<String> SPAWN_FIELDS = Set.of("schema", "id", "name", "description", "group", "kind", "cost", "count", "hostile",
		"cooldown_s", "mob", "rec", "faction", "heavy", "squad");
	private static final Set<String> EVENT_FIELDS = Set.of("schema", "id", "name", "description", "cost", "warning_s", "cooldown_s", "radius",
		"scales_with_intensity", "night_city", "effect");
	private static @Nullable ContentKind<SpawnEntry> spawns;
	private static @Nullable ContentKind<EventEntry> events;

	private OverlordContent() {
	}

	/** Once, at common init (before the server loads its datapacks). */
	static void register() {
		spawns = ContentKind.register(SPAWN_KIND, OverlordContent::parseSpawn, false);
		events = ContentKind.register(EVENT_KIND, OverlordContent::parseEvent, false);
	}

	public static Map<Identifier, SpawnEntry> spawns() {
		ContentKind<SpawnEntry> k = spawns;
		return k == null ? Map.of() : Content.all(k);
	}

	public static Map<Identifier, EventEntry> events() {
		ContentKind<EventEntry> k = events;
		return k == null ? Map.of() : Content.all(k);
	}

	static SpawnEntry parseSpawn(final Identifier id, final JsonObject json) {
		only(json, SPAWN_FIELDS);
		String name = text(json, "name", 48);
		String description = text(json, "description", 200);
		String group = oneOf(json, "group", GROUPS);
		String kind = oneOf(json, "kind", Set.of("minecraft", "night_city"));
		int cost = integer(json, "cost", 1, 60);
		int count = integer(json, "count", 1, 8);
		boolean hostile = bool(json, "hostile");
		int cooldown = json.has("cooldown_s") ? integer(json, "cooldown_s", 0, 300) : 0;
		if (kind.equals("minecraft")) {
			for (String nc : new String[] {"rec", "faction", "heavy", "squad"}) {
				if (json.has(nc)) {
					throw new IllegalArgumentException(nc + " is only for night_city entries");
				}
			}

			return new SpawnEntry(name, description, group, false, cost, count, hostile, cooldown, null, "", false, identifier(json, "mob"), count);
		}

		if (json.has("mob")) {
			throw new IllegalArgumentException("mob is only for minecraft entries (a night_city entry has squad.mob)");
		}

		String rec = oneOf(json, "rec", RECS);
		String faction = text(json, "faction", 32);
		if (!faction.matches("[A-Za-z0-9_]+")) {
			throw new IllegalArgumentException("faction must be letters, digits and _");
		}

		boolean heavy = json.has("heavy") && bool(json, "heavy");
		JsonObject squad = object(json, "squad");
		only(squad, Set.of("mob", "count"));
		return new SpawnEntry(name, description, group, true, cost, count, hostile, cooldown, rec, faction, heavy, identifier(squad, "mob"),
			integer(squad, "count", 1, 8));
	}

	static EventEntry parseEvent(final Identifier id, final JsonObject json) {
		only(json, EVENT_FIELDS);
		String name = text(json, "name", 48);
		String description = text(json, "description", 200);
		int cost = integer(json, "cost", 1, 100);
		int warning = integer(json, "warning_s", 3, 30);
		int cooldown = integer(json, "cooldown_s", 0, 900);
		int radius = integer(json, "radius", 4, 128);
		boolean scales = bool(json, "scales_with_intensity");
		boolean nightCity = bool(json, "night_city");
		return new EventEntry(name, description, cost, warning, cooldown, radius, scales, nightCity, effect(object(json, "effect")));
	}

	static Effect effect(final JsonObject e) {
		String type = oneOf(e, "type", EFFECTS);
		return switch (type) {
			case "blackout" -> {
				only(e, Set.of("type", "seconds"));
				yield simple(type, integer(e, "seconds", 5, 120), false);
			}
			case "emp" -> {
				only(e, Set.of("type", "seconds", "cyberware_off"));
				yield simple(type, integer(e, "seconds", 3, 60), bool(e, "cyberware_off"));
			}
			case "traffic_jam" -> {
				only(e, Set.of("type", "seconds"));
				yield simple(type, integer(e, "seconds", 10, 300), false);
			}
			case "airstrike" -> {
				only(e, Set.of("type", "strikes", "spread", "power", "interval_ticks"));
				yield new Effect(type, 0, false, integer(e, "strikes", 1, 20), integer(e, "spread", 2, 32), number(e, "power", 1.0, 4.0),
					integer(e, "interval_ticks", 2, 40), 0, List.of(), "", null, 0);
			}
			case "supply_drop" -> {
				only(e, Set.of("type", "eddies", "items"));
				yield new Effect(type, 0, false, 0, 0, 0.0, 0, integer(e, "eddies", 0, 2000), stacks(e), "", null, 0);
			}
			case "weather" -> {
				only(e, Set.of("type", "weather", "seconds"));
				yield new Effect(type, integer(e, "seconds", 30, 600), false, 0, 0, 0.0, 0, 0, List.of(), oneOf(e, "weather", WEATHERS), null, 0);
			}
			default -> {
				only(e, Set.of("type", "spawn", "count"));
				yield new Effect(type, 0, false, 0, 0, 0.0, 0, 0, List.of(), "", identifier(e, "spawn"), integer(e, "count", 1, 4));
			}
		};
	}

	private static Effect simple(final String type, final int seconds, final boolean cyberwareOff) {
		return new Effect(type, seconds, cyberwareOff, 0, 0, 0.0, 0, 0, List.of(), "", null, 0);
	}

	private static List<Stack> stacks(final JsonObject e) {
		JsonElement items = e.get("items");
		if (items == null || !items.isJsonArray() || items.getAsJsonArray().size() > 8) {
			throw new IllegalArgumentException("items must be a list of at most 8");
		}

		List<Stack> out = new ArrayList<>();
		for (JsonElement i : (JsonArray) items) {
			if (!i.isJsonObject()) {
				throw new IllegalArgumentException("items holds {item, count} objects");
			}

			JsonObject o = i.getAsJsonObject();
			only(o, Set.of("item", "count"));
			out.add(new Stack(identifier(o, "item"), integer(o, "count", 1, 64)));
		}

		return List.copyOf(out);
	}

	// ---- field helpers (each throws IllegalArgumentException with the reason, as the content loader expects) ----

	private static void only(final JsonObject json, final Set<String> allowed) {
		for (String key : json.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException("unknown field " + key);
			}
		}
	}

	private static String text(final JsonObject json, final String key, final int maxBytes) {
		String s = Content.string(json, key, maxBytes);
		if (s.isBlank()) {
			throw new IllegalArgumentException(key + " must not be empty");
		}

		return s;
	}

	private static String oneOf(final JsonObject json, final String key, final Set<String> values) {
		String s = Content.string(json, key, 32);
		if (!values.contains(s)) {
			throw new IllegalArgumentException(key + " must be one of " + new java.util.TreeSet<>(values));
		}

		return s;
	}

	private static int integer(final JsonObject json, final String key, final int min, final int max) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be a whole number");
		}

		double d = e.getAsDouble();
		if (d != Math.rint(d) || d < min || d > max) {
			throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max);
		}

		return (int) d;
	}

	private static double number(final JsonObject json, final String key, final double min, final double max) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !(e.getAsDouble() >= min && e.getAsDouble() <= max)) {
			throw new IllegalArgumentException(key + " must be a number from " + min + " to " + max);
		}

		return e.getAsDouble();
	}

	private static boolean bool(final JsonObject json, final String key) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be true or false");
		}

		return e.getAsBoolean();
	}

	private static JsonObject object(final JsonObject json, final String key) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException(key + " must be an object");
		}

		return e.getAsJsonObject();
	}

	private static Identifier identifier(final JsonObject json, final String key) {
		Identifier id = Identifier.tryParse(Content.string(json, key, 128));
		if (id == null) {
			throw new IllegalArgumentException(key + " must be a namespaced id");
		}

		return id;
	}
}
