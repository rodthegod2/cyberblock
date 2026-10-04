package dev.cyberblock.mode.downed;

import org.jspecify.annotations.Nullable;

/** The pure decisions of Downed (megaplan 8-T2): who goes down instead of dying, and who may revive whom. */
public final class DownedRules {
	/** Trauma Team's price in Minecraft eddies (section 1.16); creative pays nothing. */
	public static final int TRAUMA_PRICE = 250;
	/** Health after a teammate's revive (3 hearts); Trauma Team heals fully. */
	public static final float REVIVE_HEALTH = 6.0F;
	/** A reviver must stay this close (blocks, eye to the downed player's position). */
	public static final double REVIVE_RANGE = 3.5;

	/** What a would-be death becomes. */
	public enum Death {
		/** goes down instead (bleed-out, revive, Trauma Team) */
		DOWN,
		/** dies as vanilla says (the bleed-out ended, the void or /kill, a totem, creative or spectator, already down) */
		DIE
	}

	private DownedRules() {
	}

	/**
	 * @param finishing the bleed-out (or a give-up, or leaving while down) is ending this player now
	 * @param down the player is already down (a hit that bypasses invulnerability, the void, /kill)
	 * @param bypasses the damage bypasses invulnerability (the void, /kill)
	 * @param totem a totem of undying in either hand (vanilla saves the player)
	 * @param exempt creative or spectator
	 */
	public static Death onDeath(final boolean finishing, final boolean down, final boolean bypasses, final boolean totem, final boolean exempt) {
		return finishing || down || bypasses || totem || exempt ? Death.DIE : Death.DOWN;
	}

	/**
	 * May {@code reviver} revive {@code downed}? Match teams first (both in the same match, and the same team when the downed player has
	 * one), else vanilla scoreboard teams (allied when the downed player is on one), else anyone. Never oneself.
	 *
	 * @param downedMatch the downed player's match id (null: none) and team (-1: none)
	 * @param reviverMatch the reviver's
	 * @param downedHasTeam the downed player is on a vanilla scoreboard team
	 * @param allied the reviver is allied to it (vanilla isAlliedTo)
	 */
	public static boolean mayRevive(final boolean self, final @Nullable String downedMatch, final int downedTeam, final @Nullable String reviverMatch,
		final int reviverTeam, final boolean downedHasTeam, final boolean allied) {
		if (self) {
			return false;
		}

		if (downedMatch != null) {
			return downedMatch.equals(reviverMatch) && (downedTeam < 0 || downedTeam == reviverTeam);
		}

		return !downedHasTeam || allied;
	}

	/** Whole seconds shown for a tick count (rounded up, so 0 only at the end). */
	public static int seconds(final long ticks) {
		return (int) Math.max(0, (ticks + 19) / 20);
	}
}
