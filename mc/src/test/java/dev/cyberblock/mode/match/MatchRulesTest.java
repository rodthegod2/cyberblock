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
		assertArrayEquals(new int[] {2, 2, 0, 0}, m.teamSizes(), "team deathmatch fills its two teams (teams.min) before a third");
		assertEquals(2, m.shownTeams());
		assertEquals(0, MatchRules.team(new int[] {3, 3}, 7, 2, 8), "an unknown pick: the smallest, first of equals");
		assertEquals(2, MatchRules.team(new int[] {3, 3, 1}, -1, 3, 8));
		assertEquals(0, MatchRules.team(new int[] {3, 3, 0, 0}, -1, 2, 8), "a third team waits until the first two are full");
		assertEquals(2, MatchRules.team(new int[] {8, 8, 0, 0}, -1, 2, 8), "both full: the next team opens");
		assertEquals(1, MatchRules.team(new int[] {8, 3}, 0, 2, 8), "a full preferred team: the smallest open one");
		assertEquals(-1, MatchRules.team(new int[] {8, 8}, -1, 2, 8), "every team full");
	}

	@Test
	void freeForAllIsATeamEach() {
		MatchMode ffa = MatchMode.parse(net.minecraft.resources.Identifier.fromNamespaceAndPath("cyberblock", "free_for_all"),
			com.google.gson.JsonParser.parseString(MatchModeTest.FREE_FOR_ALL).getAsJsonObject());
		Match m = new Match("m2", "cyberblock:free_for_all", ffa, CREATOR, "CbHost1", new Match.Zone(0, 0, 64, ""), true, false, 300, 0L);
		m.join(CREATOR, "CbHost1", Role.TEAMMATE, true, -1);
		for (int i = 1; i < 8; i++) {
			assertEquals(i, m.join(UUID.randomUUID(), "P" + i, Role.TEAMMATE, false, -1).team(), "player " + i + " gets the next team");
		}

		assertEquals(8, m.shownTeams());
		assertEquals("cyberblock.match.refused.teams_full", MatchRules.joinRefusal(m, UUID.randomUUID(), false, Role.TEAMMATE, false), "eight teams of one");
		assertNull(MatchRules.joinRefusal(m, UUID.randomUUID(), false, Role.SPECTATOR, false), "watching needs no seat");
		assertEquals("cyberblock.match.refused.role", MatchRules.joinRefusal(m, UUID.randomUUID(), false, Role.HACKER, false), "free for all has no Van");
		assertNull(MatchRules.joinRefusal(m, CREATOR, true, Role.TEAMMATE, false), "a member re-joining keeps its own seat");
	}

	@Test
	void aRunningPvpMatchHoldsItsTeammatesToggle() {
		Match m = match("cyberblock:gang_wars", false);
		UUID spec = UUID.randomUUID();
		m.join(spec, "S", Role.SPECTATOR, false, -1);
		assertFalse(MatchRules.pvpHeld(m, CREATOR), "not in the lobby");
		m.start(0L);
		assertTrue(MatchRules.pvpHeld(m, CREATOR), "a teammate of a running PvP match keeps PvP on");
		assertFalse(MatchRules.pvpHeld(m, spec), "spectators do not fight");
		assertFalse(MatchRules.pvpHeld(m, UUID.randomUUID()), "not a member");
		Match calm = new Match("m3", "cyberblock:gang_wars", m.mode, CREATOR, "CbHost1", m.zone, false, false, 300, 0L);
		calm.join(CREATOR, "CbHost1", Role.TEAMMATE, true, -1);
		calm.start(0L);
		assertFalse(MatchRules.pvpHeld(calm, CREATOR), "a match without PvP holds nothing");
		m.end(1L);
		assertFalse(MatchRules.pvpHeld(m, CREATOR), "over: the toggle is the player's again");
	}

	@Test
	void onlyTeammatesWithPvpOnHoldPointsInAPvpMatch() {
		Match m = match("cyberblock:gang_wars", false);
		Match.Member me = m.member(CREATOR);
		Match.Member hacker = m.join(UUID.randomUUID(), "H", Role.HACKER, false, -1);
		assertTrue(MatchRules.holdsPoints(m, me, true));
		assertFalse(MatchRules.holdsPoints(m, me, false), "PvP off: nobody can hurt it, so it cannot hold a point");
		assertFalse(MatchRules.holdsPoints(m, hacker, true), "the hacker is in the Van");
		Match calm = new Match("m3", "cyberblock:gang_wars", m.mode, CREATOR, "CbHost1", m.zone, false, false, 300, 0L);
		Match.Member c = calm.join(CREATOR, "CbHost1", Role.TEAMMATE, true, -1);
		assertTrue(MatchRules.holdsPoints(calm, c, false), "no PvP in the match: the toggle does not matter");
	}

	@Test
	void theLastTeamStandingWins() {
		MatchMode tdm = MatchMode.BUILT_IN.get("cyberblock:team_deathmatch");
		MatchMode lts = new MatchMode("Last Team Standing", "One life each.", 2, tdm.teams(), 1, 6, 1200, 1, 0, 0, 0, false, true, 32, 192, 0, 0, 0, false,
			false, 0.75, tdm.roles());
		Match m = new Match("m4", "cyberblock:last_team_standing", lts, CREATOR, "CbHost1", new Match.Zone(0, 0, 64, ""), true, false, 600, 0L);
		m.join(CREATOR, "CbHost1", Role.TEAMMATE, true, -1);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		m.join(a, "A", Role.TEAMMATE, false, 0);
		m.join(b, "B", Role.TEAMMATE, false, 1);
		m.start(0L);
		assertEquals(-1, MatchRules.standing(m), "nobody out yet");
		m.out.add(a);
		assertEquals(-1, MatchRules.standing(m), "team 0 still has its creator");
		m.out.add(b);
		assertEquals(0, MatchRules.standing(m), "team 1 is out: team 0 stands");
		m.out.add(CREATOR);
		assertEquals(-2, MatchRules.standing(m), "nobody left");
		Match tm = match("cyberblock:team_deathmatch", false);
		tm.out.add(CREATOR);
		assertEquals(-1, MatchRules.standing(tm), "a mode with respawn has no last team standing");
	}

	@Test
	void onlyTheCreatorStartsWithEnoughTeammates() {
		Match m = match("cyberblock:team_deathmatch", false);
		assertEquals("cyberblock.match.refused.too_few", MatchRules.startRefusal(m, CREATOR), "team deathmatch needs two");
		assertNull(MatchRules.startRefusalAnyCount(m, CREATOR), "the dev start (mpcheck) skips the count");
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
		s.score(1, 299);
		assertFalse(MatchRules.over(s, 1L));
		s.score(1, 1);
		assertTrue(MatchRules.over(s, 1L), "gang wars plays to 300");
		s.score(7, 5);
		assertArrayEquals(new int[] {0, 300, 0, 0}, s.scores(), "an unknown team scores nothing");
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
			"started", "too_few", "no_match", "not_in", "bad", "role", "teams_full"}) {
			assertNotEquals("cyberblock.match.refused." + key, MatchRules.fallback("cyberblock.match.refused." + key));
		}
	}
}
