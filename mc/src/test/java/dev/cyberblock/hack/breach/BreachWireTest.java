package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** Plan 6-B1 with protocol.md "Phase 6: payloads": the breach screen data and the act both ways; the solution stays on the server. */
class BreachWireTest {
	/** protocol.md's example of the screen data. */
	private static final String EXAMPLE = "{\"id\":12,\"dev\":\"11529215046068469770\",\"size\":5,\"grid\":[\"1C\",\"55\",\"BD\",\"E9\",\"7A\",\"55\",\"1C\",\"E9\",\"BD\","
		+ "\"FF\",\"E9\",\"7A\",\"55\",\"1C\",\"BD\",\"BD\",\"FF\",\"1C\",\"55\",\"7A\",\"7A\",\"E9\",\"FF\",\"BD\",\"1C\"],\"buffer\":6,\"picks\":[2,7,6],\"seqs\":["
		+ "{\"id\":\"open_door\",\"codes\":[\"BD\",\"1C\"],\"ok\":false},{\"id\":\"disable_cameras\",\"codes\":[\"E9\",\"BD\",\"FF\"],\"ok\":false}],\"total\":45,"
		+ "\"left\":31.5,\"state\":\"run\",\"strength\":0}";

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theProtocolExampleParses() {
		BreachWire.Screen s = BreachWire.parse(json(EXAMPLE));
		assertNotNull(s);
		assertEquals(12, s.id());
		assertEquals("11529215046068469770", s.dev());
		assertArrayEquals(new int[] {2, 7, 6}, s.picks());
		assertEquals("open_door", s.seqs().getFirst().id());
		assertArrayEquals(new int[] {2, 0}, s.seqs().getFirst().codes());
		assertEquals(31.5, s.left());
		assertFalse(s.done());
	}

	@Test
	void screenDataHasExactlyTheProtocolKeys() {
		BreachGame g = new BreachGame(BreachPuzzle.generate(7L, new BreachPuzzle.Tier(7, 8, 3, 3, 25), BreachPuzzleTest.POOL), 0);
		JsonObject m = json(BreachWire.screen(5, "77", g, 0));
		assertEquals(Set.of("id", "dev", "size", "grid", "buffer", "picks", "seqs", "total", "left", "state", "strength"), m.keySet(), "no solution, nothing else");
		assertEquals(25.0, m.get("left").getAsDouble(), "the whole timer at the opening");
		assertEquals("run", m.get("state").getAsString());
		assertEquals(Set.of("id", "codes", "ok"), m.getAsJsonArray("seqs").get(0).getAsJsonObject().keySet());
		BreachWire.Screen s = BreachWire.parse(m);
		assertNotNull(s);
		assertArrayEquals(g.puzzle().grid(), s.grid());
		assertEquals(3, s.seqs().size());
	}

	@Test
	void screenDataFollowsThePlay() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		g.pick(1, 100);
		g.pick(21, 100);
		BreachWire.Screen s = BreachWire.parse(json(BreachWire.screen(5, "77", g, 1_150)));
		assertNotNull(s);
		assertArrayEquals(new int[] {1, 21}, s.picks());
		assertTrue(s.seqs().get(0).ok());
		assertFalse(s.seqs().get(1).ok());
		assertEquals(18.9, s.left(), 0.051, "20 s from the opening at 0");
		assertEquals(0, s.strength(), "0 until the end");

		g.quit();
		BreachWire.Screen end = BreachWire.parse(json(BreachWire.screen(5, "77", g, 1_150)));
		assertNotNull(end);
		assertTrue(end.done());
		assertEquals("aborted", end.state(), "an abort after a pick is a failure");
		assertEquals(0, end.strength());
		assertTrue(end.seqs().get(0).ok(), "the screen still shows what the buffer held");
	}

	@Test
	void aKeptPuzzleShowsAbortedWhileItsTimerRuns() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		BreachWire.Screen s = BreachWire.parse(json(BreachWire.screen(5, "77", g, 4_000, true)));
		assertNotNull(s);
		assertEquals("aborted", s.state());
		assertTrue(s.done());
		assertEquals(16.0, s.left(), 0.051);
		assertFalse(g.ended(), "the game itself goes on, for the next right-click");
	}

	@Test
	void theShortBreachIsFourByFour() {
		BreachGame g = new BreachGame(BreachPuzzle.generate(3L, BreachContent.SHORT, BreachPuzzleTest.POOL), 0);
		BreachWire.Screen s = BreachWire.parse(json(BreachWire.screen(1, "0", g, 0)));
		assertNotNull(s);
		assertEquals(4, s.size(), "protocol.md: size 4 is Phase 8's short breach");
	}

	@Test
	void badScreenDataIsDropped() {
		assertNotNull(BreachWire.parse(json(EXAMPLE)));
		String[] bad = {EXAMPLE.replace("\"size\":5", "\"size\":8"), EXAMPLE.replace("\"grid\":[\"1C\",", "\"grid\":[\"1D\","), EXAMPLE.replace("\"picks\":[2,7,6]", "\"picks\":[2,2]"),
			EXAMPLE.replace("\"picks\":[2,7,6]", "\"picks\":[25]"), EXAMPLE.replace("\"state\":\"run\"", "\"state\":\"won\""), EXAMPLE.replace("\"left\":31.5", "\"left\":46"),
			EXAMPLE.replace("\"dev\":\"11529215046068469770\"", "\"dev\":\"x\""), EXAMPLE.replace("\"open_door\"", "\"Open\""), EXAMPLE.replace("\"buffer\":6", "\"buffer\":9")};
		for (String s : bad) {
			assertNull(BreachWire.parse(json(s)), s);
		}
	}

	@Test
	void requests() {
		assertEquals("{\"op\":\"pick\",\"id\":3,\"cell\":12}", BreachWire.pick(3, 12));
		assertEquals("{\"op\":\"abort\",\"id\":3}", BreachWire.abort(3));
		assertEquals(new BreachWire.Request(3, false, 12), BreachWire.request(json(BreachWire.pick(3, 12))));
		assertEquals(new BreachWire.Request(3, true, -1), BreachWire.request(json(BreachWire.abort(3))));
		assertNull(BreachWire.request(json("{\"op\":\"pick\",\"id\":3,\"cell\":49}")), "no grid has 50 cells");
		assertNull(BreachWire.request(json("{\"op\":\"pick\",\"id\":3,\"cell\":1.5}")));
		assertNull(BreachWire.request(json("{\"op\":\"solve\",\"id\":3}")));
		assertNull(BreachWire.request(json("{\"op\":\"abort\"}")));
		assertNull(BreachWire.request(json("{\"op\":\"pick\",\"id\":-1,\"cell\":1}")));
	}

	@Test
	void daemonNamesFallBackToWords() {
		assertEquals("cyberblock.daemon.disable_cameras", ((TranslatableContents) BreachWire.daemonName("disable_cameras")
			.getContents()).getKey());
		assertEquals("Disable Cameras", ((TranslatableContents) BreachWire.daemonName("disable_cameras").getContents())
			.getFallback());
	}
}
