package dev.cyberblock.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * One {@code npc_role} content entry (megaplan section 1.7: role → screen, services, hub placement; task 7-N1). The file
 * {@code data/<ns>/cyberblock/npc_role/<name>.json}:
 * <pre>
 * {"schema": 1, "screen": "bartender", "name": "Bartender",
 *  "vendor_types": ["Drinks"], "factions": [],            figures whose Phase 5 tags name one of these get this role
 *  "shops": ["cyberblock:hub_bar"],                       vendor: shop content ids (stock and sell rows)
 *  "drinks": [{"name": "...", "price": 12, "effect": "minecraft:speed", "seconds": 60, "amp": 0}],   bartender
 *  "heal_price": 5,                                       medic: eddies per missing heart
 *  "repair_price": 25, "vehicle_price": 120,              mechanic: per worn item; the host's Cyberpunk vehicle
 *  "contracts": [],                                       fixer: contract ids offered (empty: every contract)
 *  "implants": [],                                        ripperdoc: ripperdoc ids offered (empty: every implant)
 *  "hub": [{"pos": [x, y, z], "yaw": 180, "name": "...", "skin": "cyberblock:textures/entity/npc/bartender_f.png", "slim": true}]}
 * </pre>
 * {@code screen} is one of {@link #SCREENS}; {@code none} keeps the figure a plain figure. Matching is by the figure's name tag
 * (FigureLook's label): {@code role:<vendor type>} against {@code vendor_types}, else {@code faction:<affiliation>} against
 * {@code factions}, case and punctuation ignored (Phase 0 f: fixers, bartenders, medics and mechanics never come from the game, so
 * only content can name them). Hub spots are positions in the Afterlife hub dimension (7H's {@code cyberblock:afterlife}). Pure.
 */
public record NpcRole(String screen, String name, List<String> vendorTypes, List<String> factions, List<Identifier> shops, List<Drink> drinks,
	int healPrice, int repairPrice, int vehiclePrice, List<Identifier> contracts, List<Identifier> implants, List<HubSpot> hub) {
	public static final List<String> SCREENS = List.of("ripperdoc", "vendor", "fixer", "bartender", "medic", "mechanic", "none");
	static final int MAX_PRICE = 99_999, MAX_DRINK_SECONDS = 300, MAX_DRINK_AMP = 2, MAX_SPOTS = 8;

	/** A bartender's drink: a short potion effect for eddies. */
	public record Drink(String name, int price, Identifier effect, int seconds, int amp) {
	}

	/** Where a hub NPC of this role always stands (Afterlife dimension coordinates). */
	public record HubSpot(double x, double y, double z, float yaw, String name, String skin, boolean slim) {
	}

	public boolean has() {
		return !this.screen.equals("none");
	}

	static NpcRole parse(final Identifier id, final JsonObject json) {
		String screen = Content.string(json, "screen", 16);
		if (!SCREENS.contains(screen)) {
			throw new IllegalArgumentException("screen must be one of " + SCREENS);
		}

		String name = NpcJson.string(json, "name", 64, "");
		List<Drink> drinks = new ArrayList<>();
		for (JsonElement e : NpcJson.array(json, "drinks", 12)) {
			JsonObject d = NpcJson.object(e, "a drink");
			drinks.add(new Drink(Content.string(d, "name", 64), NpcJson.whole(d, "price", 1, MAX_PRICE, 10),
				NpcJson.id(Content.string(d, "effect", 128), "effect"), NpcJson.whole(d, "seconds", 5, MAX_DRINK_SECONDS, 60),
				NpcJson.whole(d, "amp", 0, MAX_DRINK_AMP, 0)));
		}

		List<HubSpot> hub = new ArrayList<>();
		for (JsonElement e : NpcJson.array(json, "hub", MAX_SPOTS)) {
			JsonObject s = NpcJson.object(e, "a hub spot");
			double[] p = Content.position(s, "pos");
			String skin = NpcJson.string(s, "skin", 128, "");
			if (!skin.isEmpty()) {
				NpcJson.id(skin, "skin");
			}

			boolean slim = s.has("slim") && s.get("slim").isJsonPrimitive() && s.get("slim").getAsBoolean();
			hub.add(new HubSpot(p[0], p[1], p[2], (float) NpcJson.number(s, "yaw", -360.0, 360.0, 0.0), NpcJson.string(s, "name", 32, name),
				skin, slim));
		}

		return new NpcRole(screen, name, NpcJson.strings(json, "vendor_types", 16, 32), NpcJson.strings(json, "factions", 16, 32),
			NpcJson.ids(json, "shops", 8), List.copyOf(drinks), NpcJson.whole(json, "heal_price", 1, MAX_PRICE, 5),
			NpcJson.whole(json, "repair_price", 1, MAX_PRICE, 25), NpcJson.whole(json, "vehicle_price", 1, MAX_PRICE, 120),
			NpcJson.ids(json, "contracts", 64), NpcJson.ids(json, "implants", 32), List.copyOf(hub));
	}

	/** Whether a figure with this name-tag label (FigureLook: "role:&lt;vendor type&gt;", "faction:&lt;affiliation&gt;") has this role. */
	boolean matches(final String label) {
		int colon = label.indexOf(':');
		if (colon < 0) {
			return false;
		}

		String kind = label.substring(0, colon), value = NpcJson.key(label.substring(colon + 1));
		List<String> list = kind.equals("role") ? this.vendorTypes : kind.equals("faction") ? this.factions : List.of();
		return !value.isEmpty() && list.stream().anyMatch(s -> NpcJson.key(s).equals(value));
	}
}
