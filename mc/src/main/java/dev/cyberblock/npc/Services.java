package dev.cyberblock.npc;

import com.google.gson.JsonObject;

/**
 * The two requests a role screen sends DOWN to a host's own Cyberpunk game (megaplan task 7-N1, for 7S's {@code Services.reds}
 * hooks, events {@code repair} and {@code heal} with {@code Cyberblock_EventId} the request id): {@code {"t":"repair","id":N}}
 * repairs the car V is in, else V's own car within 30 m; {@code {"t":"heal","id":N}} clears V's damage-over-time effects. Neither
 * answers on the wire (the scripts log their path and show a toast). Pure.
 */
final class Services {
	private Services() {
	}

	static String repair(final int id) {
		return message("repair", id);
	}

	static String heal(final int id) {
		return message("heal", id);
	}

	private static String message(final String t, final int id) {
		JsonObject m = new JsonObject();
		m.addProperty("t", t);
		m.addProperty("id", id);
		return m.toString();
	}
}
