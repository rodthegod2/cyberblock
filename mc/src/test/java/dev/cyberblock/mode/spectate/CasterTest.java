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
			"Chaos", List.of(new CasterWire.Row("CbGuest1", 42)), List.of(new CasterWire.Feed("CbHost1", "downed", "CbGuest1", 3)));
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
	}

	@Test
	void writingCapsTheLists() {
		List<CasterWire.Row> rows = new java.util.ArrayList<>();
		for (int i = 0; i < CasterWire.ROWS + 5; i++) {
			rows.add(new CasterWire.Row("p" + i, i));
		}

		CasterWire.View v = CasterWire.parse(CasterWire.write(new CasterWire.View(true, "", -1, 0, 0, List.of(), "", rows, List.of())));
		assertEquals(CasterWire.ROWS, v.board().size());
	}
}
