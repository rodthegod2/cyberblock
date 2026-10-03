package dev.cyberblock.cyber;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;

/** Minecraft to Cyberpunk messages of layer 6 (plan 2026-10-03 section 1.2), one JSON object each. */
public final class CyberWire {
	private CyberWire() {
	}

	/** {"t":"binds","keys":{"hack":vk,"skills":vk}}: Windows virtual-key codes 1..254. */
	public static String binds(final int hackVk, final int skillsVk) {
		JsonObject keys = new JsonObject();
		keys.addProperty("hack", hackVk);
		keys.addProperty("skills", skillsVk);
		JsonObject m = new JsonObject();
		m.addProperty("t", "binds");
		m.add("keys", keys);
		return m.toString();
	}

	/** {"t":"perks","list":[{"id":"iron_skin","rank":0|1}, ...]}: all 20 perks in branch order. */
	public static String perks(final Map<String, Integer> ranks) {
		JsonArray list = new JsonArray();
		for (Perk perk : Perk.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("id", perk.id());
			entry.addProperty("rank", ranks.getOrDefault(perk.id(), 0) > 0 ? 1 : 0);
			list.add(entry);
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "perks");
		m.add("list", list);
		return m.toString();
	}

	/**
	 * {"t":"cyberware","list":[{"id":"mantis_blades","on":0|1}, ...]}: all 17 implants in catalogue order (plan retest2 section
	 * 1.2), on for the installed ones (ids not in the catalogue are ignored).
	 */
	public static String cyberware(final Set<String> installed) {
		JsonArray list = new JsonArray();
		for (Cyberware c : Cyberware.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("id", c.id());
			entry.addProperty("on", installed.contains(c.id()) ? 1 : 0);
			list.add(entry);
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "cyberware");
		m.add("list", list);
		return m.toString();
	}

	/** {"t":"perkfx","id":"sandevistan","seconds":s}: a perk's Cyberpunk effect fires now (seconds 0..30). */
	public static String perkfx(final String id, final double seconds) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "perkfx");
		m.addProperty("id", id);
		m.addProperty("seconds", Math.clamp(seconds, 0.0, 30.0));
		return m.toString();
	}

	/** {"t":"hack","id":n,"target":"<id>","hack":"short_circuit","chain":bool}. */
	public static String hack(final int id, final long target, final Hack hack, final boolean chain) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "hack");
		m.addProperty("id", id);
		m.addProperty("target", Long.toUnsignedString(target));
		m.addProperty("hack", hack.wire());
		m.addProperty("chain", chain);
		return m.toString();
	}

	/** {"t":"scan","id":n,"target":"<id>"|null}: null asks Cyberpunk for whatever V looks at. */
	public static String scan(final int id, final long target) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "scan");
		m.addProperty("id", id);
		if (target == 0L) {
			m.add("target", JsonNull.INSTANCE);
		} else {
			m.addProperty("target", Long.toUnsignedString(target));
		}

		return m.toString();
	}
}
