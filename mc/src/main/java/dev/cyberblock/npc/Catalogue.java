package dev.cyberblock.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.cyber.Cyberware;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * One {@code ripperdoc} content entry: a clinic's implant catalogue (megaplan section 1.7 and task 7-N1; 7A's
 * {@code shared/content-schemas/ripperdoc.schema.json}):
 * <pre>
 * {"schema": 1, "title_key": "cyberblock.afterlife.clinic",
 *  "implants": [{"implant": "lynx_paws", "price": 900}, {"implant": "sandevistan", "price": 4800, "surgery": {"window": 18, "period": 32}}],
 *  "failure": {"damage": 6, "debuff_s": 30}}
 * </pre>
 * {@code implant} is a cyberware id, {@code price} Minecraft eddies. An implant with {@code surgery} goes in through the timing bar:
 * the marker runs the bar and back once every {@code period} ticks, and the cut must land in the centre {@code window} (percent of
 * the bar; smaller is riskier). A missed cut costs {@code failure.damage} half-hearts (never below half a heart) and a debuff of
 * {@code debuff_s} seconds. Pure.
 */
public record Catalogue(String titleKey, List<Offer> offers, int failDamage, int debuffSeconds) {
	/** One implant the clinic installs: window 0 means no surgery. */
	public record Offer(Cyberware cyberware, int price, int window, int periodTicks) {
		public boolean surgery() {
			return this.window > 0;
		}
	}

	static Catalogue parse(final Identifier id, final JsonObject json) {
		String titleKey = NpcJson.string(json, "title_key", 96, "");
		List<Offer> offers = new ArrayList<>();
		for (JsonElement e : NpcJson.array(json, "implants", 32)) {
			JsonObject o = NpcJson.object(e, "an implant");
			String name = Content.string(o, "implant", 32);
			Cyberware cyberware = Cyberware.byId(name);
			if (cyberware == null) {
				throw new IllegalArgumentException("implant " + name + " is not a cyberware id");
			}

			int window = 0, period = 0;
			if (o.has("surgery")) {
				JsonObject s = NpcJson.object(o.get("surgery"), "surgery");
				window = NpcJson.whole(s, "window", 5, 90, 30);
				period = NpcJson.whole(s, "period", 10, 200, 60);
			}

			offers.add(new Offer(cyberware, NpcJson.whole(o, "price", 1, NpcRole.MAX_PRICE, 1), window, period));
		}

		if (offers.isEmpty()) {
			throw new IllegalArgumentException("implants must list 1 to 32 implants");
		}

		JsonObject failure = json.has("failure") ? NpcJson.object(json.get("failure"), "failure") : new JsonObject();
		return new Catalogue(titleKey, List.copyOf(offers), NpcJson.whole(failure, "damage", 0, 20, 6), NpcJson.whole(failure, "debuff_s", 0, 600, 30));
	}
}
