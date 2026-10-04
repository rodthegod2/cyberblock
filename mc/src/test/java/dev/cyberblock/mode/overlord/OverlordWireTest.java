package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Overlord Console's act and ui messages (megaplan section 1.3). */
class OverlordWireTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void anAskRoundTrips() {
		OverlordWire.Ask a = OverlordWire.parseAsk(json(OverlordWire.ask("cyberblock:tyger_claws", 12.5, -40.0)));
		assertEquals(new OverlordWire.Ask("cyberblock:tyger_claws", 12.5, -40.0), a);
	}

	@Test
	void badAsksAreDropped() {
		assertNull(OverlordWire.parseAsk(json("{\"id\":\"a:b\",\"x\":1}")));
		assertNull(OverlordWire.parseAsk(json("{\"id\":\"\",\"x\":1,\"z\":2}")));
		assertNull(OverlordWire.parseAsk(json("{\"id\":\"a:b\",\"x\":4e7,\"z\":2}")));
		assertNull(OverlordWire.parseAsk(json("{\"id\":5,\"x\":1,\"z\":2}")));
		assertNull(OverlordWire.parseAsk(json("{\"id\":\"" + "a".repeat(200) + "\",\"x\":1,\"z\":2}")));
	}

	@Test
	void theStateRoundTrips() {
		OverlordWire.State s = new OverlordWire.State(true, "m1", 42, 120, 2.0, "normal", 3, 12, 12, true, new double[] {-10, -20, 30, 40},
			List.of(new OverlordWire.Player("Alice", 1, 2, true, false), new OverlordWire.Player("Rod", 5, 6, false, true)),
			List.of(new OverlordWire.Option("cyberblock:tyger_claws", "Tyger Claws", "Blades out.", "gang", true, 14, 3, 0, true)),
			List.of(new OverlordWire.Option("cyberblock:airstrike", "Airstrike", "Boom.", "airstrike", false, 30, 0, 12, false)),
			List.of(new OverlordWire.Mark("spawn", "Tyger Claws", 20, 0, 40, 0)),
			List.of(new OverlordWire.Line(3, "Auto-Director: Tyger Claws at 20 0 (lands in 3 s)")), "");
		OverlordWire.State back = OverlordWire.parseState(json(OverlordWire.state(s)));
		assertNotNull(back);
		assertTrue(back.open());
		assertEquals(42, back.tp());
		assertEquals("normal", back.intensity());
		assertArrayEquals(new double[] {-10, -20, 30, 40}, back.area());
		assertEquals(s.players(), back.players());
		assertEquals(s.spawns(), back.spawns());
		assertEquals(s.events(), back.events());
		assertEquals(s.pending(), back.pending());
		assertEquals(s.timeline(), back.timeline());
		assertTrue(back.director());
	}

	@Test
	void aStateWithoutAnAreaOrWithBadEntriesStillParses() {
		OverlordWire.State back = OverlordWire.parseState(json("{\"match\":\"m\",\"intensity\":\"chill\",\"regen\":1,"
			+ "\"players\":[{\"name\":\"A\",\"x\":1},{\"name\":\"B\",\"x\":1,\"z\":2}],\"spawns\":[{\"name\":\"no id\"}]}"));
		assertNotNull(back);
		assertNull(back.area());
		assertEquals(1, back.players().size(), "the entry without z is skipped");
		assertTrue(back.spawns().isEmpty());
		assertNull(OverlordWire.parseState(json("{\"intensity\":\"chill\",\"regen\":1}")), "no match");
		assertNull(OverlordWire.parseState(json("{\"match\":\"m\",\"intensity\":\"chill\",\"regen\":1,\"area\":[1,2,\"x\",4]}")));
	}

	@Test
	void longTextIsCutOnACharacterBoundary() {
		assertEquals("ab", OverlordWire.cap("abc", 2));
		assertEquals("é", OverlordWire.cap("éé", 3), "é is two bytes");
		assertEquals("abc", OverlordWire.cap("abc", 10));
	}
}
