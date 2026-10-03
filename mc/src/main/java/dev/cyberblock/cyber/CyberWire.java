package dev.cyberblock.cyber;

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
}
