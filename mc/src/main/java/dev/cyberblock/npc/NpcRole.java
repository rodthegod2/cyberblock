package dev.cyberblock.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One {@code npc_role} content entry (megaplan section 1.7 and task 7-N1; 7A's {@code shared/content-schemas/npc_role.schema.json}):
 * <pre>
 * {"schema": 1, "title_key": "cyberblock.role.bartender", "screen": "bartender", "services": ["rumours", "drinks"],
 *  "tags": ["Drinks", "bartender"], "dialogue": "cyberblock:bartender",
 *  "shop": "cyberblock:afterlife_gear", "shop_by_tag": {"Gunsmith": "cyberblock:afterlife_arms"},     shop screens
 *  "catalogue": "cyberblock:afterlife",                                                                 ripperdoc
 *  "prices": {"heal_per_heart": 6, "repair_vehicle": 120, "repair_gear": 40},                            medic, mechanic
 *  "drinks": [{"key": "neon_sour", "price": 12, "effect": "minecraft:speed", "seconds": 90, "amplifier": 0}],
 *  "hub": {"area": "bar"}}
 * </pre>
 * {@code screen} is one of {@link #SCREENS}, {@code services} what it offers ({@link #SERVICES}). A Night City figure has the role when
 * its Phase 5 role tag (its name tag {@code role:<vendor type>}) is one of {@code tags}, case and punctuation ignored (Phase 0 f: only
 * vendor types come from the game; fixers, bartenders, medics and mechanics live in the hub's {@code figures}). A drink's name is the
 * lang key {@code cyberblock.drink.<key>}. Pure.
 */
public record NpcRole(String titleKey, String screen, Set<String> services, List<String> tags, Identifier dialogue, @Nullable Identifier shop,
	Map<String, Identifier> shopByTag, @Nullable Identifier catalogue, int healPerHeart, int repairVehicle, int repairGear, List<Drink> drinks,
	String hubArea) {
	public static final List<String> SCREENS = List.of("ripperdoc", "shop", "contracts", "bartender", "medic", "mechanic");
	public static final List<String> SERVICES = List.of("install_implant", "surgery", "buy", "sell", "gigs", "rumours", "drinks", "heal", "repair_vehicle",
		"repair_gear");
	static final int MAX_PRICE = 99_999;

	/** A bartender's drink: a short potion effect for eddies; its name is the lang key cyberblock.drink.&lt;key&gt;. */
	public record Drink(String key, int price, Identifier effect, int amplifier, int seconds) {
	}

	static NpcRole parse(final Identifier id, final JsonObject json) {
		String titleKey = Content.string(json, "title_key", 96);
		if (!titleKey.matches("[a-z0-9_.]+")) {
			throw new IllegalArgumentException("title_key must be a lang key");
		}

		String screen = Content.string(json, "screen", 16);
		if (!SCREENS.contains(screen)) {
			throw new IllegalArgumentException("screen must be one of " + SCREENS);
		}

		Set<String> services = new LinkedHashSet<>(NpcJson.strings(json, "services", 10, 16));
		if (services.isEmpty() || !SERVICES.containsAll(services)) {
			throw new IllegalArgumentException("services must hold 1 to 10 of " + SERVICES);
		}

		List<String> tags = NpcJson.strings(json, "tags", 32, 48);
		if (tags.isEmpty()) {
			throw new IllegalArgumentException("tags must hold at least one role tag");
		}

		Identifier dialogue = NpcJson.id(Content.string(json, "dialogue", 128), "dialogue");
		Identifier shop = json.has("shop") ? NpcJson.id(Content.string(json, "shop", 128), "shop") : null;
		Map<String, Identifier> byTag = new LinkedHashMap<>();
		if (json.has("shop_by_tag")) {
			JsonObject o = NpcJson.object(json.get("shop_by_tag"), "shop_by_tag");
			for (String tag : o.keySet()) {
				byTag.put(tag, NpcJson.id(Content.string(o, tag, 128), "shop_by_tag." + tag));
			}
		}

		Identifier catalogue = json.has("catalogue") ? NpcJson.id(Content.string(json, "catalogue", 128), "catalogue") : null;
		JsonObject prices = json.has("prices") ? NpcJson.object(json.get("prices"), "prices") : new JsonObject();
		List<Drink> drinks = new ArrayList<>();
		for (JsonElement e : NpcJson.array(json, "drinks", 12)) {
			JsonObject d = NpcJson.object(e, "a drink");
			String key = Content.string(d, "key", 32);
			if (!key.matches("[a-z0-9_]+")) {
				throw new IllegalArgumentException("a drink's key is a-z, 0-9 and _");
			}

			drinks.add(new Drink(key, NpcJson.whole(d, "price", 1, 9999, 10), NpcJson.id(Content.string(d, "effect", 128), "effect"),
				NpcJson.whole(d, "amplifier", 0, 4, 0), NpcJson.whole(d, "seconds", 5, 600, 60)));
		}

		String area = json.has("hub") ? Content.string(NpcJson.object(json.get("hub"), "hub"), "area", 32) : "";
		return new NpcRole(titleKey, screen, Set.copyOf(services), tags, dialogue, shop, Map.copyOf(byTag), catalogue,
			NpcJson.whole(prices, "heal_per_heart", 1, 9999, 5), NpcJson.whole(prices, "repair_vehicle", 1, MAX_PRICE, 120),
			NpcJson.whole(prices, "repair_gear", 1, MAX_PRICE, 40), List.copyOf(drinks), area);
	}

	public boolean offers(final String service) {
		return this.services.contains(service);
	}

	/** The role tag in a figure's name-tag label ("role:&lt;vendor type&gt;"), "" when it has none. */
	static String tag(final String label) {
		return label.startsWith("role:") ? label.substring(5) : "";
	}

	/** Whether a figure with this name-tag label has this role. */
	boolean matches(final String label) {
		String tag = NpcJson.key(tag(label));
		return !tag.isEmpty() && this.tags.stream().anyMatch(t -> NpcJson.key(t).equals(tag));
	}

	/** The shop a figure with this role tag sells from: its {@code shop_by_tag} entry, else {@code shop} (null: none). */
	@Nullable Identifier shopFor(final String tag) {
		String key = NpcJson.key(tag);
		for (Map.Entry<String, Identifier> e : this.shopByTag.entrySet()) {
			if (!key.isEmpty() && NpcJson.key(e.getKey()).equals(key)) {
				return e.getValue();
			}
		}

		return this.shop;
	}
}
