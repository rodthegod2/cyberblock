package dev.cyberblock.cyber;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

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
