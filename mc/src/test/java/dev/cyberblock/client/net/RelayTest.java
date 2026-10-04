package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RelayTest {
	/** Megaplan section 1.2, Cyberpunk to Minecraft: every existing t and its class. */
	@ParameterizedTest
	@CsvSource({
		"hello, BOTH", "cam, LOCAL", "ground, UP", "unground, UP", "clear, UP", "state, BOTH", "view, LOCAL", "hud, LOCAL",
		"aim, BOTH", "key, LOCAL", "slot, LOCAL", "scroll, LOCAL", "cursor, LOCAL", "click, LOCAL", "char, LOCAL", "skey, LOCAL",
		"dmg, UP", "npcs, BOTH", "mobdmg, UP", "pcells, UP", "unlead, UP", "killed, UP", "scaninfo, UP", "hackresult, UP",
		"hackspread, UP", "look, LOCAL", "stats, BOTH", "quest, LOCAL", "pins, LOCAL", "prompt, LOCAL", "choices, LOCAL",
		"sub, LOCAL", "call, LOCAL", "notify, LOCAL", "screen, LOCAL", "reply, LOCAL", "terrain, LOCAL", "surveystate, LOCAL",
		"cmd, DEV",
		// not a DLL message: never relayed (the server's whitelist would drop it anyway)
		"hstate, LOCAL", "session, LOCAL", "nonsense, LOCAL"
	})
	void everyMessageHasItsRoute(final String t, final Relay.Route route) {
		assertEquals(route, Relay.route(t));
	}

	@Test
	void aMessageThatFitsIsSentUnchanged() {
		String raw = "{\"t\":\"ground\",\"c\":[1,2,3,4]}";
		assertEquals(List.of(raw), Relay.split("ground", parse(raw), raw, 100));
	}

	@Test
	void groundIsSplitBetweenWholeQuadruplesUnderTheCap() {
		JsonObject m = new JsonObject();
		m.addProperty("t", "ground");
		JsonArray c = new JsonArray();
		for (int i = 0; i < 4000; i++) {
			c.add(i * 7 - 9000);
		}

		m.add("c", c);
		String raw = m.toString();
		int cap = 3000;
		List<String> chunks = Relay.split("ground", m, raw, cap);
		assertTrue(chunks.size() > 1);
		List<Integer> joined = new ArrayList<>();
		for (String chunk : chunks) {
			assertTrue(Relay.bytes(chunk) <= cap, "chunk of " + Relay.bytes(chunk) + " bytes");
			JsonObject part = parse(chunk);
			assertEquals("ground", part.get("t").getAsString());
			JsonArray cells = part.getAsJsonArray("c");
			assertEquals(0, cells.size() % 4, "whole quadruples only");
			cells.forEach(e -> joined.add(e.getAsInt()));
		}

		List<Integer> all = new ArrayList<>();
		c.forEach(e -> all.add(e.getAsInt()));
		assertEquals(all, joined, "every cell once, in order");
	}

	@Test
	void npcsAreSplitByWholeEntriesKeepingTheOtherFields() {
		JsonObject m = new JsonObject();
		m.addProperty("t", "npcs");
		m.addProperty("extra", 1);
		JsonArray list = new JsonArray();
		for (int i = 0; i < 300; i++) {
			list.add(parse("{\"id\":\"" + (1000000 + i) + "\",\"p\":[1.5,2.5,3.5],\"yaw\":90,\"kind\":\"human\",\"hostile\":false}"));
		}

		m.add("list", list);
		List<String> chunks = Relay.split("npcs", m, m.toString(), 2000);
		int n = 0;
		for (String chunk : chunks) {
			JsonObject part = parse(chunk);
			assertTrue(Relay.bytes(chunk) <= 2000);
			assertEquals(1, part.get("extra").getAsInt());
			n += part.getAsJsonArray("list").size();
		}

		assertEquals(300, n);
	}

	@Test
	void anUnsplittableMessageOverTheCapIsDropped() {
		String raw = "{\"t\":\"dmg\",\"kind\":\"" + "x".repeat(200) + "\"}";
		assertTrue(Relay.split("dmg", parse(raw), raw, 100).isEmpty());
		String ground = "{\"t\":\"ground\",\"c\":[1,2,3]}" + " ".repeat(100);
		assertTrue(Relay.split("ground", parse(ground), ground, 50).isEmpty(), "not whole quadruples");
	}

	@Test
	void theCapCountsUtf8Bytes() {
		String raw = "{\"t\":\"npcs\",\"list\":[{\"id\":\"ééé\"}]}";
		assertEquals(raw.length() + 3, Relay.bytes(raw));
	}

	private static JsonObject parse(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}
}
