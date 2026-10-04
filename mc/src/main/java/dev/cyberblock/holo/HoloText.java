package dev.cyberblock.holo;

import net.minecraft.network.chat.Component;

/**
 * The Holo-Table's player-facing texts with their English fallbacks (megaplan rule 0.14: 7A owns the lang file; every key here
 * shows in English until it is there). Shared by the server's chat lines and the client's spawn list.
 */
public final class HoloText {
	private HoloText() {
	}

	/** A spawn refusal ({@link HoloRules#refusal}'s keys, plus gone and no_ground); {@code wait} fills the cooldown's seconds. */
	public static Component refusal(final String key, final long wait) {
		return switch (key) {
			case "cyberblock.holo.pending" -> Component.translatableWithFallback(key, "A Holo-Table spawn is already on its way");
			case "cyberblock.holo.cooldown" -> Component.translatableWithFallback(key, "Wait %s s before the next Holo-Table spawn", wait);
			case "cyberblock.holo.team_only" -> Component.translatableWithFallback(key, "In a match you can only spawn at your team's spawns");
			case "cyberblock.holo.locked_zone" -> Component.translatableWithFallback(key, "That spot is inside a locked match zone");
			case "cyberblock.holo.self" -> Component.translatableWithFallback(key, "That is you");
			case "cyberblock.holo.friend_hidden" -> Component.translatableWithFallback(key, "That player cannot be joined right now");
			case "cyberblock.holo.friend_away" -> Component.translatableWithFallback(key, "That player is not in Night City");
			case "cyberblock.holo.held" -> Component.translatableWithFallback(key, "Spawn refused: the game holds V right now (a scene or a ride)");
			case "cyberblock.holo.vehicle" -> Component.translatableWithFallback(key, "Spawn refused: V is in a vehicle");
			case "cyberblock.holo.no_ground" -> Component.translatableWithFallback(key, "Night City is not built there yet (no ground under that spot)");
			default -> Component.translatableWithFallback("cyberblock.holo.gone", "That spawn point is gone");
		};
	}

	/** vteleported.reason as text for the player. */
	public static Component reason(final String reason) {
		return switch (reason) {
			case "" -> Component.translatableWithFallback("cyberblock.holo.reason.unknown", "Cyberpunk gave no reason");
			case "no ground" -> Component.translatableWithFallback("cyberblock.holo.reason.no_ground", "no ground under that spot in Night City");
			case "timeout" -> Component.translatableWithFallback("cyberblock.holo.reason.timeout", "Night City did not load there in time");
			default -> reason.startsWith("blocked (") && reason.endsWith(")")
				? Component.translatableWithFallback("cyberblock.holo.reason.blocked", "blocked (%s)", reason.substring(9, reason.length() - 1))
				: Component.literal(reason);
		};
	}
}
