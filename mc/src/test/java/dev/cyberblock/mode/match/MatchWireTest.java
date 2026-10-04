package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.mode.match.MatchWire.Request;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: the Match Terminal's act and ui messages. */
class MatchWireTest {
	private static Request req(final String json) {
		return MatchWire.request(JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void create() {
		Request r = req("{\"op\":\"create\",\"mode\":\"cyberblock:gang_wars\",\"zone\":{\"x\":-1200.5,\"z\":300,\"r\":128,\"district\":\"Watson\"},"
			+ "\"time\":900,\"pvp\":true,\"invite\":false}");
		assertNotNull(r);
		assertEquals("cyberblock:gang_wars", r.mode());
		assertEquals(new Match.Zone(-1200.5, 300, 128, "Watson"), r.zone());
		assertEquals(900, r.time());
		assertTrue(r.pvp());
		assertFalse(r.invite());
	}

	@Test
	void badCreatesAreDropped() {
		String ok = "\"mode\":\"cyberblock:gang_wars\",\"time\":900,\"pvp\":true,\"invite\":false";
		assertNull(req("{\"op\":\"create\"," + ok + "}"), "no zone");
		assertNull(req("{\"op\":\"create\"," + ok + ",\"zone\":{\"x\":0,\"z\":0,\"r\":8}}"), "radius under 16");
		assertNull(req("{\"op\":\"create\"," + ok + ",\"zone\":{\"x\":0,\"z\":0,\"r\":600}}"), "radius over 512");
		assertNull(req("{\"op\":\"create\"," + ok + ",\"zone\":{\"x\":4e7,\"z\":0,\"r\":64}}"), "outside the world");
		assertNull(req("{\"op\":\"create\",\"mode\":\"Gang Wars\",\"time\":900,\"pvp\":true,\"invite\":false,\"zone\":{\"x\":0,\"z\":0,\"r\":64}}"));
		assertNull(req("{\"op\":\"create\",\"mode\":\"cyberblock:gang_wars\",\"time\":30,\"pvp\":true,\"invite\":false,\"zone\":{\"x\":0,\"z\":0,\"r\":64}}"));
		assertNull(req("{\"op\":\"create\",\"mode\":\"cyberblock:gang_wars\",\"time\":900,\"invite\":false,\"zone\":{\"x\":0,\"z\":0,\"r\":64}}"), "no pvp");
	}

	@Test
	void joinInviteAndTheRest() {
		Request j = req("{\"op\":\"join\",\"id\":\"m12\",\"role\":\"overlord\"}");
		assertEquals(Match.Role.OVERLORD, j.role());
		assertEquals(-1, j.team());
		assertEquals(1, req("{\"op\":\"join\",\"id\":\"m1\",\"team\":1}").team());
		assertEquals(Match.Role.TEAMMATE, req("{\"op\":\"join\",\"id\":\"m1\"}").role(), "teammate by default");
		assertNull(req("{\"op\":\"join\",\"id\":\"m1\",\"role\":\"king\"}"));
		assertNull(req("{\"op\":\"join\",\"id\":\"../x\"}"));
		assertNull(req("{\"op\":\"join\",\"id\":\"m1\",\"team\":4}"));
		assertEquals("CbGuest_1", req("{\"op\":\"invite\",\"name\":\"CbGuest_1\"}").name());
		assertNull(req("{\"op\":\"invite\",\"name\":\"two words\"}"));
		for (String op : List.of("open", "leave", "start", "end")) {
			assertEquals(op, req("{\"op\":\"" + op + "\"}").op());
		}

		assertNull(req("{\"op\":\"explode\"}"));
		assertNull(req("{}"));
	}

	@Test
	void stateRoundTrip() {
		MatchWire.Team red = new MatchWire.Team("Tyger Claws", 0xE040A0, 12, List.of(new MatchWire.Member("CbHost1", "teammate", true),
			new MatchWire.Member("CbGuest1", "hacker", false)));
		MatchWire.Team blue = new MatchWire.Team("Maelstrom", 0xD02020, 7, List.of());
		MatchWire.View v = new MatchWire.View("m1", "cyberblock:gang_wars", "Gang Wars", "CbHost1", "running", false, true, true, -1200.0, 300.0, 128.0,
			"Watson", 512, -1, List.of(red, blue), List.of(new MatchWire.Member("CbGuest2", "spectator", false)),
			List.of(new MatchWire.Point("Market", -1180.0, 400.0, 6.0, 0, 0, 0.75)));
		MatchWire.State s = new MatchWire.State(true, false, "cyberblock.match.refused.not_linked", "m1", 0, "hacker", true, 9,
			List.of(new MatchWire.Mode("cyberblock:gang_wars", "Gang Wars", "Hold it.", List.of(new MatchWire.Team("Tyger Claws", 0xE040A0, 0, List.of())),
				true, 900, "capture", 128)),
			List.of(new MatchWire.District("Watson", -1200.0, 300.0, 140.0)), List.of(v));
		MatchWire.State back = MatchWire.parse(MatchWire.json(s));
		assertEquals(s, back);
		assertEquals(v, back.mine());
	}
}
