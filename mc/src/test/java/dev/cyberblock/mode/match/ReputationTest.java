package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.mode.match.Match.Member;
import dev.cyberblock.mode.match.Match.Role;
import dev.cyberblock.mode.match.Reputation.Change;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1 with Phase 0 (e): Gang Wars reputation as attitude DOWN messages. */
class ReputationTest {
	private static final MatchMode WARS = MatchMode.BUILT_IN.get("cyberblock:gang_wars");
	private static final UUID CLAW = UUID.randomUUID(), MAEL = UUID.randomUUID(), GUEST = UUID.randomUUID(), SPEC = UUID.randomUUID();
	private static final List<Member> MEMBERS = List.of(new Member(CLAW, "Claw", Role.TEAMMATE, 0, true), new Member(MAEL, "Mael", Role.TEAMMATE, 1, true),
		new Member(GUEST, "Guest", Role.TEAMMATE, 0, false), new Member(SPEC, "Spec", Role.SPECTATOR, -1, false));

	@Test
	void winnersAreFriendsOfTheirGangAndEnemiesOfTheLosers() {
		List<Change> out = Reputation.afterMatch(WARS, MEMBERS, 0);
		assertEquals(List.of(new Change(CLAW, "tygerClaws", "friendly"), new Change(CLAW, "maelstrom", "hostile"),
			new Change(MAEL, "maelstrom", "neutral"), new Change(MAEL, "tygerClaws", "hostile")), out);
	}

	@Test
	void onlyLinkedTeammatesHaveAGameToChange() {
		assertTrue(Reputation.afterMatch(WARS, MEMBERS, 1).stream().noneMatch(c -> c.host().equals(GUEST) || c.host().equals(SPEC)));
	}

	@Test
	void aDrawOrGanglessTeamsChangeNothing() {
		assertTrue(Reputation.afterMatch(WARS, MEMBERS, -1).isEmpty());
		assertTrue(Reputation.afterMatch(MatchMode.BUILT_IN.get("cyberblock:team_deathmatch"), MEMBERS, 0).isEmpty());
	}

	@Test
	void theMessageIsTheContractShape() {
		var o = JsonParser.parseString(new Change(CLAW, "6thStreet", "hostile").json()).getAsJsonObject();
		assertEquals("attitude", o.get("t").getAsString());
		assertEquals("6thStreet", o.get("faction").getAsString());
		assertEquals("hostile", o.get("toward").getAsString());
		assertEquals(3, o.size());
	}
}
