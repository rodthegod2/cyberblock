package dev.cyberblock.mode.match;

import org.jspecify.annotations.Nullable;

/**
 * The PvP rule of megaplan 8-M1, pure and tested: a hit from one player on another applies only when <b>both</b> have their PvP
 * toggle on; in a match, teammates do not hurt each other unless the match allows friendly fire. After the last PvP hit (dealt or
 * taken) a player waits 15 s before the toggle may go off. Hits on a linked player land on Steve's hearts like every other hit (the
 * one health rule), so the rule only decides whether the hit happens.
 */
public final class PvpRules {
	/** After the last PvP hit, the toggle stays on this long (ms). */
	public static final long LOCK_MS = 15_000L;
	/** No PvP hit yet. */
	public static final long NEVER = Long.MIN_VALUE;

	/** Why a hit between two players does not happen; each has a lang key for the attacker's action bar. */
	public enum Refusal {
		ATTACKER_OFF("cyberblock.pvp.refused.you_off", "Your PvP is off: turn it on to hurt players"),
		VICTIM_OFF("cyberblock.pvp.refused.they_off", "That player's PvP is off"),
		TEAMMATE("cyberblock.pvp.refused.teammate", "That player is on your team");

		public final String key;
		public final String fallback;

		Refusal(final String key, final String fallback) {
			this.key = key;
			this.fallback = fallback;
		}
	}

	private PvpRules() {
	}

	/**
	 * Null when the hit applies.
	 *
	 * @param sameTeam both players are on the same team of the same match
	 * @param friendlyFire that match lets teammates hurt each other
	 */
	public static @Nullable Refusal refusal(final boolean attackerOn, final boolean victimOn, final boolean sameTeam, final boolean friendlyFire) {
		if (!attackerOn) {
			return Refusal.ATTACKER_OFF;
		}

		if (!victimOn) {
			return Refusal.VICTIM_OFF;
		}

		return sameTeam && !friendlyFire ? Refusal.TEAMMATE : null;
	}

	/** How long (ms) the toggle must still stay on after the last PvP hit at {@code lastHit}; 0 when it may go off. */
	public static long lockLeft(final long now, final long lastHit) {
		if (lastHit == NEVER) {
			return 0L;
		}

		return Math.max(0L, lastHit + LOCK_MS - now);
	}

	/** Whole seconds for a lock left in ms (rounded up, so "1 s" shows until it is really over). */
	public static int seconds(final long ms) {
		return (int) ((ms + 999L) / 1000L);
	}

	/** What the toggle becomes when the player asks for {@code wanted}: off is refused while locked. */
	public static boolean next(final boolean current, final boolean wanted, final long now, final long lastHit) {
		if (wanted) {
			return true;
		}

		return current && lockLeft(now, lastHit) > 0L;
	}
}
