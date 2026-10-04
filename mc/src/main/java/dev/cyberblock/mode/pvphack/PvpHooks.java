package dev.cyberblock.mode.pvphack;

import dev.cyberblock.net.ServerConfig;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerPlayer;

/**
 * What player hacks ask package 8M (megaplan 8-M1), built in parallel: the PvP toggle, the Gang Wars war zones and the match teams.
 * 8M (or the Phase 8 integrator) installs its rules with {@link #use}; until then the defaults hold: PvP is the server's
 * {@code pvpDefault} (section 1.13: off unless the server says otherwise), there are no war zones and no teams. Server thread.
 */
public final class PvpHooks {
	private static volatile Predicate<ServerPlayer> pvpOn = p -> ServerConfig.get().pvpDefault();
	private static volatile Predicate<ServerPlayer> warZone = p -> false;
	private static volatile ToIntFunction<ServerPlayer> team = p -> -1;
	private static volatile String source = "";

	private PvpHooks() {
	}

	/**
	 * 8M's rules: whether a player's PvP toggle is on, whether he stands inside a war zone, and his match team (-1: none); {@code by}
	 * names them for the start-up line.
	 */
	public static void use(final Predicate<ServerPlayer> pvp, final Predicate<ServerPlayer> zone, final ToIntFunction<ServerPlayer> teamOf,
		final String by) {
		pvpOn = pvp;
		warZone = zone;
		team = teamOf;
		source = by;
	}

	static boolean pvpOn(final ServerPlayer player) {
		return pvpOn.test(player);
	}

	static boolean inWarZone(final ServerPlayer player) {
		return warZone.test(player);
	}

	static int team(final ServerPlayer player) {
		return team.applyAsInt(player);
	}

	/** The start-up line's words for which rules are active. */
	static String describe() {
		return source.isEmpty() ? "PvP from the server's pvpDefault (" + ServerConfig.get().pvpDefault() + "; 8M's toggle not installed), no war zones, no teams"
			: "PvP, war zones and teams from " + source;
	}
}
