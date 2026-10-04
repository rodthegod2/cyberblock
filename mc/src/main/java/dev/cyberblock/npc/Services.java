package dev.cyberblock.npc;

import com.google.gson.JsonObject;

/**
 * The request a role screen sends DOWN to a host's own Cyberpunk game (megaplan task 7-N1, for 7S's {@code Services.reds} hook,
 * event {@code heal} with {@code Cyberblock_EventId} the request id): {@code {"t":"heal","id":N}} clears V's damage-over-time effects
 * after the medic healed Steve (paid and done in Minecraft; nothing answers on the wire). Not yet a section 1.9 row: reported as a
 * contract deviation for the integrator, as is a future car repair, which is not sent at all until it can be answered. Pure.
 */
final class Services {
	private Services() {
	}

	static String heal(final int id) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "heal");
		m.addProperty("id", id);
		return m.toString();
	}
}
