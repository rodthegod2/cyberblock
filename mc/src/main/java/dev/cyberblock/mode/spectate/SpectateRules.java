package dev.cyberblock.mode.spectate;

import org.jspecify.annotations.Nullable;

/** The pure decision of who may start spectating now (megaplan 8-T3; {@link Spectate} feeds it the player's state). */
final class SpectateRules {
	/** No spectating within this long of a hit or of a mob's attention (server ticks: 10 s). */
	static final long COMBAT_TICKS = 200;
	/** The command again only this long after the last stop (server ticks: 60 s). */
	static final long COOLDOWN_TICKS = 1200;

	/** Why a player may not spectate now (each a lang key and its English). */
	enum Refusal {
		HOST("cyberblock.spectate.host", "Spectating is for Minecraft-only players: your Cyberpunk link is on"),
		DOWN("cyberblock.spectate.down", "You cannot spectate while you are down"),
		ALREADY("cyberblock.spectate.already", "You are already spectating (/cyberblock spectate stop)"),
		MATCH("cyberblock.spectate.match", "You are playing a match: spectate from its Match Terminal"),
		FIGHT("cyberblock.spectate.fight", "You cannot spectate in a fight: wait 10 s after the last hit"),
		UNSAFE("cyberblock.spectate.unsafe", "You cannot spectate while falling, gliding, burning or in lava"),
		COOLDOWN("cyberblock.spectate.cooldown", "You can spectate again in a minute");

		final String key;
		final String english;

		Refusal(final String key, final String english) {
			this.key = key;
			this.english = english;
		}
	}

	private SpectateRules() {
	}

	/**
	 * Why a player may not spectate now, or null when it may.
	 *
	 * @param host a linked host (its Steve belongs to its V)
	 * @param matchPlayer it plays a running match (not as a spectator or overlord)
	 * @param fromTerminal 8M's Match Terminal starts it for a match (the match decides who watches; no cooldown)
	 * @param sinceHurt server ticks since it was last hurt (Long.MAX_VALUE: never)
	 * @param targeted a mob nearby has it as its target
	 * @param unsafe falling, gliding, burning or in lava
	 * @param sinceStop server ticks since it last stopped spectating (Long.MAX_VALUE: never)
	 */
	static @Nullable Refusal refusal(final boolean host, final boolean down, final boolean already, final boolean matchPlayer, final boolean fromTerminal,
		final long sinceHurt, final boolean targeted, final boolean unsafe, final long sinceStop) {
		if (host) {
			return Refusal.HOST;
		}

		if (down) {
			return Refusal.DOWN;
		}

		if (already) {
			return Refusal.ALREADY;
		}

		if (matchPlayer && !fromTerminal) {
			return Refusal.MATCH;
		}

		if (sinceHurt < COMBAT_TICKS || targeted) {
			return Refusal.FIGHT;
		}

		if (unsafe) {
			return Refusal.UNSAFE;
		}

		return !fromTerminal && sinceStop < COOLDOWN_TICKS ? Refusal.COOLDOWN : null;
	}
}
