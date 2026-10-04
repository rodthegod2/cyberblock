package dev.cyberblock.mode.match;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who may create and join matches, team assignment, the end of a match and the winner (megaplan 8-M1), pure and tested. Every
 * refusal is a lang key; {@link #fallback} gives its English.
 */
public final class MatchRules {
	public static final int MAX_OVERLORDS = 2;
	public static final int MIN_TIME = 60;
	/** The time limit range (seconds; 8A's schema: time_limit_s 60..7200). */
	public static final int MAX_TIME = 7200;
	/** A lobby nobody started is closed after this long (ms). */
	public static final long LOBBY_MS = 15L * 60L * 1000L;
	/** An ended match stays listed (with its result) this long (ms). */
	public static final long RESULT_MS = 60_000L;
	/** At most this many matches at once on a server. */
	public static final int MAX_MATCHES = 8;
	public static final double MIN_RADIUS = 16.0;
	/** The zone radius range a request may carry (8A's schema: zone 16..1024); the mode's own range then clamps it. */
	public static final double MAX_RADIUS = 1024.0;

	private MatchRules() {
	}

	/** Why this player may not create a match, or null. Only Cyberpunk-linked players create matches. */
	public static @Nullable String createRefusal(final boolean linked, final boolean inMatch, final int matches) {
		if (!linked) {
			return "cyberblock.match.refused.not_linked";
		}

		if (inMatch) {
			return "cyberblock.match.refused.in_match";
		}

		return matches >= MAX_MATCHES ? "cyberblock.match.refused.full" : null;
	}

	/**
	 * Why this player may not join {@code m} as {@code role}, or null. Linked players fight (V is theirs): they join as teammates;
	 * Minecraft-only players pick teammate, hacker, spectator or overlord.
	 *
	 * @param otherMatch the player is already in another match
	 */
	public static @Nullable String joinRefusal(final Match m, final UUID who, final boolean linked, final Match.Role role, final boolean otherMatch) {
		if (m.state() == Match.State.ENDED) {
			return "cyberblock.match.refused.ended";
		}

		if (otherMatch) {
			return "cyberblock.match.refused.in_match";
		}

		if (m.inviteOnly && !m.invited(who)) {
			return "cyberblock.match.refused.invite_only";
		}

		if (linked && role != Match.Role.TEAMMATE) {
			return "cyberblock.match.refused.linked_fights";
		}

		if (!m.mode.allows(role)) {
			return "cyberblock.match.refused.role";
		}

		Match.Member already = m.member(who);
		boolean overlordNow = already != null && already.role() == Match.Role.OVERLORD;
		if (role == Match.Role.OVERLORD && !overlordNow && m.count(Match.Role.OVERLORD) >= MAX_OVERLORDS) {
			return "cyberblock.match.refused.overlords_full";
		}

		return role.onTeam() && m.teamFor(who, role, -1) < 0 ? "cyberblock.match.refused.teams_full" : null;
	}

	/**
	 * A teammate of a running PvP match keeps PvP on: it consented by joining, and turning it off would make it unhurtable while it
	 * still fights and holds capture points. Leaving the match gives the toggle back.
	 */
	public static boolean pvpHeld(final Match m, final UUID who) {
		Match.Member member = m.member(who);
		return m.state() == Match.State.RUNNING && m.pvp && member != null && member.role() == Match.Role.TEAMMATE && !m.isOut(who);
	}

	/** Whether a member standing in a capture point counts for its team: a teammate still in, with PvP on when the match is a PvP one. */
	public static boolean holdsPoints(final Match m, final Match.Member member, final boolean pvpOn) {
		return member.role() == Match.Role.TEAMMATE && !m.isOut(member.uuid()) && (!m.pvp || pvpOn);
	}

	/**
	 * Last team standing (modes with {@code respawn_s} 0): once somebody is out, the only team that still has a teammate in wins.
	 * The team, -1 while more than one stands (or nobody is out yet), -2 when nobody is left.
	 */
	public static int standing(final Match m) {
		if (m.mode.respawnS() > 0 || m.out.isEmpty()) {
			return -1;
		}

		int last = -2;
		for (Match.Member member : m.members.values()) {
			if (member.role() == Match.Role.TEAMMATE && !m.isOut(member.uuid()) && member.team() >= 0 && member.team() != last) {
				if (last >= 0) {
					return -1;
				}

				last = member.team();
			}
		}

		return last;
	}

	/** Why the player may not start {@code m}, or null: only its creator, only from the lobby, only with enough teammates. */
	public static @Nullable String startRefusal(final Match m, final UUID who) {
		String why = startRefusalAnyCount(m, who);
		return why == null && m.count(Match.Role.TEAMMATE) < m.mode.minPlayers() ? "cyberblock.match.refused.too_few" : why;
	}

	/** {@link #startRefusal} without the player count (the dev command {@code /cyberblock test match start}). */
	public static @Nullable String startRefusalAnyCount(final Match m, final UUID who) {
		if (!who.equals(m.creator)) {
			return "cyberblock.match.refused.not_creator";
		}

		return m.state() != Match.State.LOBBY ? "cyberblock.match.refused.started" : null;
	}

	/**
	 * The team to put a new team member on: the preferred one when it exists and has room, else the smallest (the first of equals)
	 * of the first {@code minTeams}; when those are all full at {@code sizeMax} the next team opens. -1 when every team is full.
	 */
	public static int team(final int[] sizes, final int preferred, final int minTeams, final int sizeMax) {
		if (preferred >= 0 && preferred < sizes.length && sizes[preferred] < sizeMax) {
			return preferred;
		}

		for (int open = Math.min(Math.max(1, minTeams), sizes.length); open <= sizes.length; open++) {
			int best = -1;
			for (int t = 0; t < open; t++) {
				if (sizes[t] < sizeMax && (best < 0 || sizes[t] < sizes[best])) {
					best = t;
				}
			}

			if (best >= 0) {
				return best;
			}
		}

		return -1;
	}

	/** The team with the single highest score, or -1 for a draw. */
	public static int winner(final int[] scores) {
		int best = -1;
		boolean tie = false;
		for (int t = 0; t < scores.length; t++) {
			if (best < 0 || scores[t] > scores[best]) {
				best = t;
				tie = false;
			} else if (scores[t] == scores[best]) {
				tie = true;
			}
		}

		return tie ? -1 : best;
	}

	/** A running match ends when its time is up or a team reached the score limit (0 = none). */
	public static boolean over(final Match m, final long now) {
		if (m.state() != Match.State.RUNNING) {
			return false;
		}

		if (now >= m.endsMs) {
			return true;
		}

		if (m.mode.scoreLimit() > 0) {
			for (int s : m.scores) {
				if (s >= m.mode.scoreLimit()) {
					return true;
				}
			}
		}

		return false;
	}

	/** A lobby that waited too long, or an ended match whose result has been shown long enough, leaves the list. */
	public static boolean expired(final Match m, final long now) {
		return m.state() == Match.State.LOBBY && now - m.createdMs >= LOBBY_MS || m.state() == Match.State.ENDED && now - m.endedMs >= RESULT_MS;
	}

	/** The English of a refusal key (Component.translatableWithFallback). */
	public static String fallback(final String key) {
		return switch (key) {
			case "cyberblock.match.refused.not_linked" -> "Only a player whose Cyberpunk is linked can create a match";
			case "cyberblock.match.refused.in_match" -> "You are already in a match: leave it first";
			case "cyberblock.match.refused.full" -> "There are already as many matches as this server runs";
			case "cyberblock.match.refused.ended" -> "That match is over";
			case "cyberblock.match.refused.invite_only" -> "That match is invite only";
			case "cyberblock.match.refused.linked_fights" -> "Your V fights: a linked player joins as a teammate";
			case "cyberblock.match.refused.overlords_full" -> "That match already has two overlords";
			case "cyberblock.match.refused.role" -> "That match mode has no such role";
			case "cyberblock.match.refused.teams_full" -> "Every team of that match is full";
			case "cyberblock.match.refused.not_creator" -> "Only the player who created the match can do that";
			case "cyberblock.match.refused.started" -> "That match has already started";
			case "cyberblock.match.refused.too_few" -> "Not enough teammates yet to start";
			case "cyberblock.match.refused.no_match" -> "There is no such match";
			case "cyberblock.match.refused.not_in" -> "You are not in a match";
			case "cyberblock.match.refused.bad" -> "That request was not understood";
			default -> key;
		};
	}
}
