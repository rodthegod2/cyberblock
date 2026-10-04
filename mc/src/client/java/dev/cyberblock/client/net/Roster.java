package dev.cyberblock.client.net;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The server's player list ({@code cyberblock:roster}, megaplan section 1.3): who is linked (a Cyberpunk host), body, team, PvP,
 * downed, hidden. Phase 5 reads it for cyber skins, the HUD for markers. Replaced whole on every update; any thread may read it.
 */
public final class Roster {
	/** One player; {@code body} is male or female, {@code team} -1 for none. */
	public record Entry(UUID uuid, String name, boolean linked, String body, int team, boolean pvp, boolean downed, boolean hidden) {
	}

	private static volatile Map<UUID, Entry> byUuid = Map.of();
	private static volatile List<Entry> list = List.of();

	private Roster() {
	}

	/** The newest list from the server (client thread); a repeated uuid keeps its last entry. */
	public static void set(final List<Entry> entries) {
		list = List.copyOf(entries);
		byUuid = entries.stream().collect(Collectors.toUnmodifiableMap(Entry::uuid, Function.identity(), (a, b) -> b));
	}

	/** Left the server: nobody is known. */
	public static void clear() {
		set(List.of());
	}

	public static List<Entry> all() {
		return list;
	}

	public static @Nullable Entry of(final UUID uuid) {
		return byUuid.get(uuid);
	}

	/** The player is a linked host (shown with the cyber skin and the [V] tag). */
	public static boolean linked(final UUID uuid) {
		Entry e = byUuid.get(uuid);
		return e != null && e.linked();
	}
}
