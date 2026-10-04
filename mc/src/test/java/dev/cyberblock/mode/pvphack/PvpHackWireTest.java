package dev.cyberblock.mode.pvphack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.cyber.Hack;
import org.junit.jupiter.api.Test;

/** The {@code cyberblock:ui pvphack} messages and the {@code glitch} DOWN (megaplan 8-X1, section 1.9). */
class PvpHackWireTest {
	private static PvpHackWire.Msg parse(final String json) {
		return PvpHackWire.parse(JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void buildersRoundTrip() {
		assertEquals("{\"k\":\"intrusion\",\"from\":\"Jackie\",\"hack\":\"short_circuit\",\"ms\":8500,\"breach\":true}",
			PvpHackWire.intrusion("Jackie", Hack.SHORT_CIRCUIT, 8500, true));
		PvpHackWire.Msg m = parse(PvpHackWire.intrusion("Jackie", Hack.SHORT_CIRCUIT, 8500, true));
		assertEquals(new PvpHackWire.Msg(PvpHackWire.Kind.INTRUSION, "Jackie", Hack.SHORT_CIRCUIT, 8500, -1, true, ""), m);
		assertEquals(new PvpHackWire.Msg(PvpHackWire.Kind.GLITCH, "", null, 5000, -1, false, ""), parse(PvpHackWire.glitch(5000)));
		assertEquals(new PvpHackWire.Msg(PvpHackWire.Kind.PING, "", null, 10000, 42, false, ""), parse(PvpHackWire.ping(42, 10000)));
		assertEquals(new PvpHackWire.Msg(PvpHackWire.Kind.END, "", null, 0, -1, false, "blocked"), parse(PvpHackWire.end("blocked")));
	}

	@Test
	void badMessagesAreDropped() {
		assertNull(parse("{\"k\":\"nope\",\"ms\":1}"));
		assertNull(parse("{\"k\":\"glitch\",\"ms\":-1}"));
		assertNull(parse("{\"k\":\"glitch\",\"ms\":30001}"));
		assertNull(parse("{\"k\":\"glitch\"}"));
		assertNull(parse("{\"k\":\"ping\",\"ms\":100}"));
		assertNull(parse("{\"k\":\"intrusion\",\"from\":\"" + "x".repeat(33) + "\",\"hack\":\"ping\",\"ms\":100,\"breach\":false}"));
		assertNull(parse("{\"k\":\"intrusion\",\"from\":\"a\",\"hack\":\"warp\",\"ms\":100,\"breach\":false}"));
		assertNull(parse("{\"k\":\"end\",\"why\":\"" + "y".repeat(17) + "\"}"));
		assertNull(parse("{\"k\":3}"));
	}

	@Test
	void glitchDownIsWholeSecondsZeroToTen() {
		JsonObject m = JsonParser.parseString(PvpHackWire.glitchDown(5)).getAsJsonObject();
		assertEquals("glitch", m.get("t").getAsString());
		assertEquals(5, m.get("seconds").getAsInt());
		assertEquals(2, m.size());
		assertEquals("{\"t\":\"glitch\",\"seconds\":10}", PvpHackWire.glitchDown(99));
		assertEquals("{\"t\":\"glitch\",\"seconds\":0}", PvpHackWire.glitchDown(-3));
		assertNotNull(PvpHackWire.UI);
		assertEquals(true, PvpHackWire.UI.length() <= 16);
	}
}
