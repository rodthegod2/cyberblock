package dev.cyberblock.holo;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table's rules (megaplan 7-T1), pure and tested: who sees whose pin, and when a spawn from the table is refused.
 *
 * <p><b>Privacy.</b> Outside matches every pin is visible except one its player hid ("hide my pin"); in a match a player sees
 * only its own team's pins plus the pins a scan revealed to it; spectators and overlords see every pin. A player who is not in a
 * match does not see the pins of players inside one (their positions are the match's business), unless it is a spectator or an
 * overlord. Everyone always sees its own pin.
 *
 * <p><b>Spawning.</b> 60 s between two spawns of one player; 5 s of spawn protection after one; never into a locked match zone (a
 * match area the player is not part of, Phase 8's hook); in a match only the team's spawns (team bases and the team's Spawn
 * Beacons); "join a friend" only to a friend whose pin the player may see and who is in Night City; a linked player's V must not
 * be held by the game or sitting in a vehicle (the same refusals as Meet Up). Spectators (in a match) may use any spawn.
 */
public final class HoloRules {
	public static final long COOLDOWN_MS = 60_000L;
	public static final long PROTECT_MS = 5_000L;
	/** vteleported.p further than this (horizontally) from the requested spot is not believed: Steve goes to the requested spot. */
	static final double LANDING_SLACK = 32.0;

	/** What a player is in Phase 8's modes; PLAYER outside matches and for the players of a match. */
	public enum Role {
		PLAYER, SPECTATOR, OVERLORD;

		public String key() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** The spawn point kinds the table offers. */
	public enum Kind {
		LANDMARK, BEACON, BASE, FRIEND;

		public String key() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}

		public static @Nullable Kind of(final String key) {
			for (Kind k : values()) {
				if (k.key().equals(key)) {
					return k;
				}
			}

			return null;
		}
	}

	/** A player as privacy sees it: its match (null = none), its team in that match (-1 none), its role, its hidden pin. */
	public record Who(UUID uuid, @Nullable String match, int team, Role role, boolean hidden) {
		public boolean inMatch() {
			return this.match != null;
		}

		/** Sees every pin: a spectator or an overlord. */
		public boolean seesAll() {
			return this.role != Role.PLAYER;
		}
	}

	private HoloRules() {
	}

	/** May {@code viewer} see {@code target}'s pin? {@code revealed}: a scan revealed the target to the viewer (Phase 8 hook). */
	public static boolean pinVisible(final Who viewer, final Who target, final boolean revealed) {
		if (viewer.uuid().equals(target.uuid()) || viewer.seesAll()) {
			return true;
		}

		if (viewer.inMatch()) {
			return revealed || sameTeam(viewer, target);
		}

		return !target.inMatch() && !target.hidden();
	}

	/** Both in the same match and on the same (real) team. */
	static boolean sameTeam(final Who a, final Who b) {
		return a.inMatch() && a.match().equals(b.match()) && a.team() >= 0 && a.team() == b.team();
	}

	/** Is a spawn owned by (match, team) one of the viewer's team spawns? */
	public static boolean teamSpawn(final Who viewer, final @Nullable String match, final int team) {
		return viewer.inMatch() && viewer.match().equals(match) && team >= 0 && team == viewer.team();
	}

	/** Whole seconds until this player may spawn again (0 = now). */
	public static long cooldownLeft(final @Nullable Long lastSpawn, final long now) {
		return lastSpawn == null ? 0 : Math.max(0, (lastSpawn + COOLDOWN_MS - now + 999) / 1000);
	}

	/** Is the player still protected after a spawn at {@code spawnedAt}? */
	public static boolean protectedNow(final @Nullable Long spawnedAt, final long now) {
		return spawnedAt != null && now >= spawnedAt && now - spawnedAt < PROTECT_MS;
	}

	/**
	 * What a spawn needs to know, for {@link #refusal}: {@code teamSpawn} (the point belongs to the viewer's team in its match),
	 * {@code lockedZone} (the point lies in a locked match zone), for FRIEND whether the friend is the viewer itself, visible to it
	 * and in Night City, and for a linked player with an active Cyberpunk whether V is held or in a vehicle.
	 */
	public record Ask(Kind kind, boolean teamSpawn, boolean lockedZone, boolean self, boolean friendVisible, boolean friendInCity, boolean held,
		boolean vehicle) {
	}

	/** The lang key why this spawn is refused, or null when it may go. Order: busy, cooldown, the match rules, the target, V. */
	public static @Nullable String refusal(final Who viewer, final Ask ask, final long cooldownLeft, final boolean pending) {
		if (pending) {
			return "cyberblock.holo.pending";
		}

		if (cooldownLeft > 0) {
			return "cyberblock.holo.cooldown";
		}

		boolean spectator = viewer.role() == Role.SPECTATOR && viewer.inMatch();
		if (!spectator && viewer.inMatch() && !ask.teamSpawn()) {
			return "cyberblock.holo.team_only";
		}

		if (!spectator && ask.lockedZone() && !ask.teamSpawn()) {
			return "cyberblock.holo.locked_zone";
		}

		if (ask.kind() == Kind.FRIEND) {
			if (ask.self()) {
				return "cyberblock.holo.self";
			}

			if (!ask.friendVisible()) {
				return "cyberblock.holo.friend_hidden";
			}

			if (!ask.friendInCity()) {
				return "cyberblock.holo.friend_away";
			}
		}

		if (ask.held()) {
			return "cyberblock.holo.held";
		}

		return ask.vehicle() ? "cyberblock.holo.vehicle" : null;
	}

	/** Where Steve goes after V's teleport: V's snapped spot, unless it is more than 32 blocks (horizontally) from the asked one. */
	public static double[] landing(final double @Nullable [] answered, final double[] asked) {
		if (answered == null || Math.hypot(answered[0] - asked[0], answered[2] - asked[2]) > LANDING_SLACK) {
			return asked;
		}

		return answered;
	}
}
