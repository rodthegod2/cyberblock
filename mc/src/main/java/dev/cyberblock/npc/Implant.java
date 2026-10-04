package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import dev.cyberblock.cyber.Cyberware;
import net.minecraft.resources.Identifier;

/**
 * One {@code ripperdoc} content entry: an implant a ripperdoc installs, its price and its surgery risk (megaplan section 1.7 and task
 * 7-N1). The file {@code data/<ns>/cyberblock/ripperdoc/<name>.json}:
 * <pre>
 * {"schema": 1, "implant": "sandevistan", "price": 900, "risk": 0.6}
 * </pre>
 * {@code implant} is a cyberware id (the fixed list of {@code shared/protocol.md}); {@code price} Minecraft eddies for the implant
 * and its install (a player who brings the implant item pays nothing); {@code risk} 0..1: above 0 the install is a surgery with the
 * timing-bar minigame (the higher, the narrower the zone and the faster the bar; failure costs health and a 30 s debuff, never a
 * life). Pure.
 */
public record Implant(Cyberware cyberware, int price, double risk) {
	static Implant parse(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "implant", 32);
		Cyberware cyberware = Cyberware.byId(name);
		if (cyberware == null) {
			throw new IllegalArgumentException("implant " + name + " is not a cyberware id");
		}

		return new Implant(cyberware, NpcJson.whole(json, "price", 0, NpcRole.MAX_PRICE, 0), NpcJson.number(json, "risk", 0.0, 1.0, 0.0));
	}

	public boolean surgery() {
		return this.risk > 0.0;
	}
}
