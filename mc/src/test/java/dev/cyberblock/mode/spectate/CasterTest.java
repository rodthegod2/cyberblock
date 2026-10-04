package dev.cyberblock.mode.spectate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 8-T3: the kill feed and the caster overlay's wire. */
class CasterTest {
	@Test
	void theKillFeedKeepsTheNewestAndForgetsTheOld() {
		KillFeed f = new KillFeed();
		for (int i = 0; i < KillFeed.CAP + 3; i++) {
			f.push("a" + i, "killed", "b" + i, i);
		}

		List<KillFeed.Entry> recent = f.recent(KillFeed.CAP + 3);
		assertEquals(KillFeed.CAP, recent.size());
		assertEquals("a" + (KillFeed.CAP + 2), recent.getFirst().who(), "newest first");
		assertEquals(List.of(), f.recent(KillFeed.CAP + 3 + KillFeed.SHOWN_TICKS + 1), "all older than 30 s");
	}

	@Test
	void theOverlayRoundTrips() {
		CasterWire.View v = new CasterWire.View(true, "Gang Wars", 125, 73, 3,
			List.of(new CasterWire.Team("Valentinos", 0xFFAA00, List.of(new CasterWire.Member("CbHost1", false), new CasterWire.Member("CbGuest1", true)))),
			"Chaos", List.of(new CasterWire.Row("CbGuest1", 42)), List.of(new CasterWire.Feed("CbHost1", "downed", "CbGuest1", 3)),
			List.of(new CasterWire.Pin("CbHost1", -1200.5, 840.25, 0xFFAA00, false), new CasterWire.Pin("CbGuest1", 10, -3, -1, true)));
		assertEquals(v, CasterWire.parse(CasterWire.write(v)));
		assertSame(CasterWire.View.OFF, CasterWire.parse(CasterWire.write(CasterWire.View.OFF)));
	}

	@Test
	void badOverlaysAreDropped() {
		assertNull(CasterWire.parse("{\"on\":true,\"timer\":-2,\"heat\":0,\"tier\":0}"));
		assertNull(CasterWire.parse("{\"on\":true,\"title\":\"\",\"timer\":-1,\"heat\":101,\"tier\":0,\"teams\":[],\"board\":{\"title\":\"\",\"rows\":[]},\"feed\":[]}"));
		StringBuilder teams = new StringBuilder("[");
		for (int i = 0; i <= CasterWire.TEAMS; i++) {
			teams.append(i == 0 ? "" : ",").append("{\"name\":\"t\",\"colour\":0,\"players\":[]}");
		}

		assertNull(CasterWire.parse("{\"on\":true,\"title\":\"\",\"timer\":-1,\"heat\":0,\"tier\":0,\"teams\":" + teams + "],\"board\":{\"title\":\"\",\"rows\":[]},\"feed\":[]}"),
			"over the team cap");
		assertNull(CasterWire.parse("nope"));
		String base = "{\"on\":true,\"title\":\"\",\"timer\":-1,\"heat\":0,\"tier\":0,\"teams\":[],\"board\":{\"title\":\"\",\"rows\":[]},\"feed\":[],\"pins\":";
		assertEquals(List.of(), CasterWire.parse(base + "[]}").pins());
		assertNull(CasterWire.parse(base + "[{\"name\":\"a\",\"x\":1e9,\"z\":0,\"colour\":-1,\"down\":false}]}"), "off the world");
		assertNull(CasterWire.parse(base + "[{\"name\":\"a\",\"x\":0,\"z\":0,\"colour\":-2,\"down\":false}]}"), "a bad colour");
	}

	private static SpectateRules.Refusal ask(final boolean matchPlayer, final boolean terminal, final long sinceHurt, final boolean targeted,
		final boolean unsafe, final long sinceStop) {
		return SpectateRules.refusal(false, false, false, matchPlayer, terminal, sinceHurt, targeted, unsafe, sinceStop);
	}

	@Test
	void spectatingIsNoEscapeHatch() {
		long never = Long.MAX_VALUE;
		assertNull(ask(false, false, never, false, false, never), "a quiet player may");
		assertEquals(SpectateRules.Refusal.HOST, SpectateRules.refusal(true, false, false, false, false, never, false, false, never));
		assertEquals(SpectateRules.Refusal.DOWN, SpectateRules.refusal(false, true, false, false, false, never, false, false, never));
		assertEquals(SpectateRules.Refusal.ALREADY, SpectateRules.refusal(false, false, true, false, false, never, false, false, never));
		assertEquals(SpectateRules.Refusal.MATCH, ask(true, false, never, false, false, never), "a match player: not by command");
		assertNull(ask(true, true, never, false, false, never), "its Match Terminal decides");
		assertEquals(SpectateRules.Refusal.FIGHT, ask(false, false, SpectateRules.COMBAT_TICKS - 1, false, false, never), "hurt 10 s ago");
		assertNull(ask(false, false, SpectateRules.COMBAT_TICKS, false, false, never));
		assertEquals(SpectateRules.Refusal.FIGHT, ask(false, false, never, true, false, never), "a squad or a mob has it as its target");
		assertEquals(SpectateRules.Refusal.FIGHT, ask(false, true, 20, false, false, never), "not from the terminal either");
		assertEquals(SpectateRules.Refusal.UNSAFE, ask(false, false, never, false, true, never), "falling, gliding, burning, lava");
		assertEquals(SpectateRules.Refusal.COOLDOWN, ask(false, false, never, false, false, SpectateRules.COOLDOWN_TICKS - 1));
		assertNull(ask(false, false, never, false, false, SpectateRules.COOLDOWN_TICKS));
		assertNull(ask(false, true, never, false, false, 20), "no cooldown for a match spectator");
	}

	@Test
	void writingCapsTheLists() {
		List<CasterWire.Row> rows = new java.util.ArrayList<>();
		for (int i = 0; i < CasterWire.ROWS + 5; i++) {
			rows.add(new CasterWire.Row("p" + i, i));
		}

		CasterWire.View v = CasterWire.parse(CasterWire.write(new CasterWire.View(true, "", -1, 0, 0, List.of(), "", rows, List.of(), List.of())));
		assertEquals(CasterWire.ROWS, v.board().size());
	}
}
