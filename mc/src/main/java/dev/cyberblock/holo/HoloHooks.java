package dev.cyberblock.holo;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the Holo-Table asks Phase 8 (matches, teams, spectators, overlords, scans, Downed), as replaceable hooks: Phase 8's packages
 * set them at init (server thread reads them). Until then nobody is in a match, nobody is downed, there are no team bases and no
 * locked zones, so the table behaves exactly as megaplan 7-T1 says outside matches. Layers are added with
 * {@link HoloLayers#register}.
 */
public final class HoloHooks {
	/** A player's place in the modes: its match id (null = none), its team in it (-1 none) and its role. */
	public record Match(@Nullable String id, int team, HoloRules.Role role) {
		public static final Match NONE = new Match(null, -1, HoloRules.Role.PLAYER);
	}

	/** A team base: a spawn of one team in one match (8-M1: Gang Wars spawn points, team bases). */
	public record Base(String id, String name, Vec3 pos, String match, int team) {
	}

	/** 8-M1/8-O1/8-T3: the player's match, team and role. */
	public static volatile Function<ServerPlayer, Match> match = player -> Match.NONE;
	/** 8-X1 Ping and scans: did a scan reveal {@code target} to {@code viewer} (in matches, a revealed pin shows)? */
	public static volatile BiPredicate<ServerPlayer, ServerPlayer> revealed = (viewer, target) -> false;
	/** 8-T2: is the player downed (pin status)? */
	public static volatile Predicate<ServerPlayer> downed = player -> false;
	/** 8-M1: the team colour of the player's pin (0xRRGGBB), -1 for none. */
	public static volatile ToIntFunction<ServerPlayer> teamColour = player -> -1;
	/** 8-M1: the team bases of the match the player is in (every team's; the rules keep only its own team's). */
	public static volatile Function<ServerPlayer, List<Base>> bases = player -> List.of();
	/** 8-M1: does {@code pos} lie in a match zone that is locked to {@code player} (a running match it is not part of)? */
	public static volatile ZoneLock lockedZone = (level, pos, player) -> false;

	@FunctionalInterface
	public interface ZoneLock {
		boolean locked(ServerLevel level, Vec3 pos, ServerPlayer player);
	}

	private HoloHooks() {
	}
}
