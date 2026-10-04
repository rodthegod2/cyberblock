package dev.cyberblock.mode.match;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gang Wars reputation (megaplan 8-M1, Phase 0 e), pure and tested: when a Gang Wars match ends with a winner, each linked host on a
 * team that stood for a gang gets {@code attitude} DOWN messages (section 1.9: {@code {faction, toward}}): the winners' own gang
 * turns friendly to them and every losing gang hostile; the losers' own gang turns neutral (they lost face) and the winners' gang
 * hostile. A draw changes nothing. Cyberpunk does not save attitude changes (the scripts use the non-persistent call), so they
 * last until the host loads a save. When check (e) fails at runtime the scripts drop the message and say so in their log: the
 * standing then stays on the Minecraft scoreboard only (the written fallback).
 */
public final class Reputation {
	public static final String FRIENDLY = "friendly", NEUTRAL = "neutral", HOSTILE = "hostile";

	/** One attitude change for one host's game. */
	public record Change(UUID host, String faction, String toward) {
		public String json() {
			return "{\"t\":\"attitude\",\"faction\":\"" + this.faction + "\",\"toward\":\"" + this.toward + "\"}";
		}
	}

	private Reputation() {
	}

	/**
	 * @param members the match's members at its end (only linked teammates count)
	 * @param winner the winning team, -1 for a draw
	 */
	public static List<Change> afterMatch(final MatchMode mode, final Iterable<Match.Member> members, final int winner) {
		List<Change> out = new ArrayList<>();
		List<MatchMode.TeamSpec> teams = mode.teams();
		if (winner < 0 || winner >= teams.size()) {
			return out;
		}

		String winnersGang = teams.get(winner).gang();
		for (Match.Member m : members) {
			if (!m.linked() || m.role() != Match.Role.TEAMMATE || m.team() < 0 || m.team() >= teams.size()) {
				continue;
			}

			String own = teams.get(m.team()).gang();
			if (m.team() == winner) {
				if (!own.isEmpty()) {
					out.add(new Change(m.uuid(), own, FRIENDLY));
				}

				for (int t = 0; t < teams.size(); t++) {
					String g = teams.get(t).gang();
					if (t != winner && !g.isEmpty() && !g.equals(own)) {
						out.add(new Change(m.uuid(), g, HOSTILE));
					}
				}
			} else {
				if (!own.isEmpty() && !own.equals(winnersGang)) {
					out.add(new Change(m.uuid(), own, NEUTRAL));
				}

				if (!winnersGang.isEmpty() && !winnersGang.equals(own)) {
					out.add(new Change(m.uuid(), winnersGang, HOSTILE));
				}
			}
		}

		return out;
	}
}
