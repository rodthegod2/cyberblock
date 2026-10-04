package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.mode.match.Match.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: who creates and joins matches, teams per match, the end of a match. */
class MatchRulesTest {
	private static final UUID CREATOR = UUID.randomUUID();

	private static Match match(final String mode, final boolean invite) {
		Match m = new Match("m1", mode, MatchMode.BUILT_IN.get(mode), CREATOR, "CbHost1", new Match.Zone(0, 0, 96, "Watson"), true, invite, 300, 1_000L);
		m.join(CREATOR, "CbHost1", Role.TEAMMATE, true, -1);
		return m;
	}

	@Test
	void onlyLinkedPlayersCreate() {
		assertNull(MatchRules.createRefusal(true, false, 0));
		assertEquals("cyberblock.match.refused.not_linked", MatchRules.createRefusal(false, false, 0));
		assertEquals("cyberblock.match.refused.in_match", MatchRules.createRefusal(true, true, 0));
		assertEquals("cyberblock.match.refused.full", MatchRules.createRefusal(true, false, MatchRules.MAX_MATCHES));
	}

	@Test
	void minecraftOnlyPlayersPickARoleLinkedPlayersFight() {
		Match m = match("cyberblock:team_deathmatch", false);
		UUID guest = UUID.randomUUID();
		for (Role role : Role.values()) {
			assertNull(MatchRules.joinRefusal(m, guest, false, role, false), role.key());
		}

		UUID host = UUID.randomUUID();
		assertNull(MatchRules.joinRefusal(m, host, true, Role.TEAMMATE, false));
		for (Role role : new Role[] {Role.HACKER, Role.SPECTATOR, Role.OVERLORD}) {
			assertEquals("cyberblock.match.refused.linked_fights", MatchRules.joinRefusal(m, host, true, role, false));
		}
	}

	@Test
	void atMostTwoOverlords() {
		Match m = match("cyberblock:gang_wars", false);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		m.join(a, "A", Role.OVERLORD, false, -1);
		m.join(b, "B", Role.OVERLORD, false, -1);
		assertEquals("cyberblock.match.refused.overlords_full", MatchRules.joinRefusal(m, c, false, Role.OVERLORD, false));
		assertNull(MatchRules.joinRefusal(m, a, false, Role.OVERLORD, false), "an overlord re-joining keeps its seat");
		assertNull(MatchRules.joinRefusal(m, c, false, Role.SPECTATOR, false));
	}

	@Test
	void inviteOnlyMatchesNeedAnInvite() {
		Match m = match("cyberblock:team_deathmatch", true);
		UUID guest = UUID.randomUUID();
		assertEquals("cyberblock.match.refused.invite_only", MatchRules.joinRefusal(m, guest, false, Role.TEAMMATE, false));
		m.invite(guest);
		assertNull(MatchRules.joinRefusal(m, guest, false, Role.TEAMMATE, false));
		assertTrue(m.invited(CREATOR), "the creator is always invited");
	}

	@Test
	void oneMatchAtATimeAndNotAfterTheEnd() {
		Match m = match("cyberblock:team_deathmatch", false);
		assertEquals("cyberblock.match.refused.in_match", MatchRules.joinRefusal(m, UUID.randomUUID(), false, Role.TEAMMATE, true));
		m.end(5_000L);
		assertEquals("cyberblock.match.refused.ended", MatchRules.joinRefusal(m, UUID.randomUUID(), false, Role.TEAMMATE, false));
	}

	@Test
	void teamsArePerMatchAndBalanced() {
		Match m = match("cyberblock:team_deathmatch", false);
		assertEquals(0, m.member(CREATOR).team());
		assertEquals(1, m.join(UUID.randomUUID(), "G1", Role.TEAMMATE, false, -1).team(), "the smallest team");
		assertEquals(0, m.join(UUID.randomUUID(), "G2", Role.HACKER, false, -1).team(), "hackers are on a team too");
		assertEquals(1, m.join(UUID.randomUUID(), "G3", Role.TEAMMATE, false, 1).team(), "a valid pick is kept");
		assertEquals(-1, m.join(UUID.randomUUID(), "S", Role.SPECTATOR, false, 0).team(), "spectators have no team");
		assertEquals(-1, m.join(UUID.randomUUID(), "O", Role.OVERLORD, false, 1).team(), "overlords have no team");
		assertArrayEquals(new int[] {2, 2}, m.teamSizes());
		assertEquals(0, MatchRules.team(new int[] {3, 3}, 7), "an unknown pick: the smallest, first of equals");
		assertEquals(2, MatchRules.team(new int[] {3, 3, 1}, -1));
	}

	@Test
	void onlyTheCreatorStartsWithEnoughTeammates() {
		Match m = match("cyberblock:team_deathmatch", false);
		assertEquals("cyberblock.match.refused.too_few", MatchRules.startRefusal(m, CREATOR), "team deathmatch needs two");
		m.join(UUID.randomUUID(), "G1", Role.SPECTATOR, false, -1);
		assertEquals("cyberblock.match.refused.too_few", MatchRules.startRefusal(m, CREATOR), "spectators do not count");
		UUID g = UUID.randomUUID();
		m.join(g, "G2", Role.TEAMMATE, false, -1);
		assertEquals("cyberblock.match.refused.not_creator", MatchRules.startRefusal(m, g));
		assertNull(MatchRules.startRefusal(m, CREATOR));
		m.start(2_000L);
		assertEquals("cyberblock.match.refused.started", MatchRules.startRefusal(m, CREATOR));
	}

	@Test
	void timeAndScoreLimitsEndAMatch() {
		Match m = match("cyberblock:gang_wars", false);
		assertFalse(MatchRules.over(m, 10_000_000L), "a lobby never runs out");
		m.start(0L);
		assertEquals(300, m.secondsLeft(0L));
		assertEquals(1, m.secondsLeft(299_001L));
		assertFalse(MatchRules.over(m, 299_999L));
		assertTrue(MatchRules.over(m, 300_000L));
		Match s = match("cyberblock:gang_wars", false);
		s.start(0L);
		s.score(1, 599);
		assertFalse(MatchRules.over(s, 1L));
		s.score(1, 1);
		assertTrue(MatchRules.over(s, 1L), "gang wars plays to 600");
		s.score(7, 5);
		assertArrayEquals(new int[] {0, 600}, s.scores(), "an unknown team scores nothing");
	}

	@Test
	void theWinnerIsTheSingleHighestScore() {
		assertEquals(1, MatchRules.winner(new int[] {3, 5}));
		assertEquals(-1, MatchRules.winner(new int[] {5, 5}));
		assertEquals(-1, MatchRules.winner(new int[] {5, 5, 3}));
		assertEquals(2, MatchRules.winner(new int[] {5, 5, 7}));
		assertEquals(-1, MatchRules.winner(new int[] {0, 0}), "nobody scored: a draw");
	}

	@Test
	void lobbiesAndResultsExpire() {
		Match m = match("cyberblock:team_deathmatch", false);
		assertFalse(MatchRules.expired(m, 1_000L + MatchRules.LOBBY_MS - 1));
		assertTrue(MatchRules.expired(m, 1_000L + MatchRules.LOBBY_MS));
		m.start(2_000L);
		assertFalse(MatchRules.expired(m, Long.MAX_VALUE / 2), "a running match never expires");
		m.end(3_000L);
		assertTrue(MatchRules.expired(m, 3_000L + MatchRules.RESULT_MS));
	}

	@Test
	void teammatesAreSameMatchSameTeam() {
		Match m = match("cyberblock:team_deathmatch", false);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), s = UUID.randomUUID();
		m.join(a, "A", Role.TEAMMATE, false, 0);
		m.join(b, "B", Role.TEAMMATE, false, 1);
		m.join(s, "S", Role.SPECTATOR, false, -1);
		assertTrue(m.teammates(CREATOR, a));
		assertFalse(m.teammates(CREATOR, b));
		assertFalse(m.teammates(s, s), "spectators are nobody's teammates");
		assertFalse(m.teammates(CREATOR, UUID.randomUUID()));
	}

	@Test
	void everyRefusalHasEnglish() {
		for (String key : new String[] {"not_linked", "in_match", "full", "ended", "invite_only", "linked_fights", "overlords_full", "not_creator",
			"started", "too_few", "no_match", "not_in", "bad"}) {
			assertNotEquals("cyberblock.match.refused." + key, MatchRules.fallback("cyberblock.match.refused." + key));
		}
	}
}
