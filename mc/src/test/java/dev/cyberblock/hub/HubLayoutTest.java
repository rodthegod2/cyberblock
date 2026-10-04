package dev.cyberblock.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: the hub layout file (7A's shared/content-schemas/hub.schema.json) as the builder reads it. */
class HubLayoutTest {
	private static final String SAMPLE = """
		{"schema": 1, "id": "cyberblock:afterlife", "title_key": "cyberblock.hub.title",
		 "origin": [0, 64, 0], "size": [32, 12, 24],
		 "spawn": {"pos": [16.5, 1, 20.5], "yaw": 180},
		 "areas": {"bar": {"from": [2, 1, 2], "to": [10, 5, 8]}},
		 "steps": [
		   {"from": [0, 0, 0], "to": [31, 0, 23], "block": "minecraft:polished_blackstone_bricks"},
		   {"from": [0, 1, 0], "to": [31, 11, 23], "block": "minecraft:black_concrete", "mode": "hollow"},
		   {"from": [4, 1, 4], "to": [4, 1, 4], "block": "minecraft:oak_stairs[facing=east,half=bottom]"}
		 ],
		 "stations": {"match_terminal": [16, 1, 3], "holo_table": [26, 1, 12], "exit_portal": [16, 1, 22]},
		 "boards": [{"pos": [10, 3, 1], "facing": "south", "objective": "cb_wins", "title_key": "cyberblock.afterlife.board.wins"}],
		 "figures": [{"id": "bartender", "role": "cyberblock:bartender", "area": "bar", "pos": [6.5, 1, 5.5], "yaw": 90, "gender": "female",
		   "name": "Nyx"}]}
		""";

	private static JsonObject sample() {
		return JsonParser.parseString(SAMPLE).getAsJsonObject();
	}

	private static JsonObject with(final Consumer<JsonObject> change) {
		JsonObject json = sample();
		change.accept(json);
		return json;
	}

	@Test
	void aLayoutFileIsRead() {
		HubLayout l = HubLayout.parse(sample());
		assertEquals("cyberblock.hub.title", l.titleKey());
		assertEquals(3, l.steps().size());
		assertTrue(l.steps().get(1).hollow());
		assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", l.steps().get(2).block());
		assertEquals(new HubLayout.Cell(16, 1, 3), l.matchTerminal());
		assertEquals(new HubLayout.Cell(16, 1, 22), l.exitPortal());
		assertEquals(1, l.boards().size());
		assertEquals("cb_wins", l.boards().getFirst().objective());
		assertEquals(1, l.figures(), "the figures are 7N's: only counted");
		assertEquals(180.0F, l.spawnYaw());
	}

	@Test
	void theOverlordConsoleStationIsOptional() {
		assertNull(HubLayout.parse(sample()).overlordConsole(), "a layout without it builds as before");
		HubLayout l = HubLayout.parse(with(j -> j.getAsJsonObject("stations").add("overlord_console", JsonParser.parseString("[18, 1, 3]"))));
		assertEquals(new HubLayout.Cell(18, 1, 3), l.overlordConsole());
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonObject("stations")
			.add("overlord_console", JsonParser.parseString("[18, 1]")))));
	}

	@Test
	void theShippedHubHasTheOverlordConsoleBesideTheMatchTerminal() throws java.io.IOException {
		try (var reader = new java.io.InputStreamReader(HubLayoutTest.class.getResourceAsStream("/data/cyberblock/cyberblock/hub/afterlife.json"),
			java.nio.charset.StandardCharsets.UTF_8)) {
			HubLayout l = HubLayout.parse(JsonParser.parseReader(reader).getAsJsonObject());
			HubLayout.Cell console = l.overlordConsole(), terminal = l.matchTerminal();
			assertNotNull(console, "8-O1: the console is a block in the hub");
			assertEquals(terminal.y(), console.y());
			assertTrue(Math.abs(console.x() - terminal.x()) + Math.abs(console.z() - terminal.z()) <= 3, "on the Match Terminal's dais");
		}

		assertNotNull(HubLayout.fallback().overlordConsole(), "the built-in platform has one too");
	}

	@Test
	void layoutCellsBecomeWorldCellsThroughTheOrigin() {
		HubLayout l = HubLayout.parse(sample());
		assertEquals(new HubLayout.Cell(16, 65, 3), l.world(l.matchTerminal()));
		assertArrayEquals(new double[] {16.5, 65.0, 20.5}, l.spawnWorld());
		assertEquals(48, l.voidY(), "16 below the floor");
	}

	@Test
	void aHollowStepFillsOnlyTheShell() {
		HubLayout.Box box = new HubLayout.Box(new HubLayout.Cell(2, 2, 2), new HubLayout.Cell(0, 0, 0));
		assertEquals(27, box.volume(), "the corners may come in any order");
		assertTrue(box.shell(0, 1, 1));
		assertTrue(box.shell(1, 1, 2));
		assertFalse(box.shell(1, 1, 1), "the middle cell stays as it is");
	}

	@Test
	void badFilesAreRefusedWithTheReason() {
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.addProperty("schema", 2))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.remove("stations"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonObject("stations").remove("exit_portal"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonArray("steps").get(0).getAsJsonObject()
			.addProperty("block", "Stone Bricks"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonArray("steps").get(0).getAsJsonObject()
			.addProperty("mode", "outline"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonArray("boards").get(0).getAsJsonObject()
			.addProperty("objective", "much_too_long_objective"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.getAsJsonArray("boards").get(0).getAsJsonObject()
			.addProperty("facing", "up"))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.add("origin", JsonParser.parseString("[0, 64.5, 0]")))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.add("origin", JsonParser.parseString("[0, 600, 0]")))));
		assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(with(j -> j.add("steps", new JsonArray()))));
	}

	@Test
	void stepsThatFillTooManyCellsAreRefused() {
		JsonObject huge = with(j -> {
			JsonArray steps = new JsonArray();
			for (int i = 0; i < 2; i++) {
				steps.add(JsonParser.parseString("{\"from\": [-512, 0, -512], \"to\": [511, 2, 511], \"block\": \"minecraft:stone\"}"));
			}

			j.add("steps", steps);
		});
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> HubLayout.parse(huge));
		assertTrue(e.getMessage().contains("cells"), e.getMessage());
	}

	@Test
	void theShippedLayoutIsReadWhenPresent() throws java.io.IOException {
		var in = HubLayoutTest.class.getResourceAsStream("/data/cyberblock/cyberblock/hub/afterlife.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "7A's layout is not in this branch yet");
		try (var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
			HubLayout l = HubLayout.parse(JsonParser.parseReader(reader).getAsJsonObject());
			for (HubLayout.Board b : l.boards()) {
				assertTrue(java.util.Set.of(Leaderboards.MATCH_WINS, Leaderboards.GIGS_DONE, Leaderboards.CHAOS).contains(b.objective()),
					"a board shows one of the objectives 7N and Phase 8 feed: " + b.objective());
			}
		}
	}

	@Test
	void theBuiltInPlatformIsAWorkingHub() {
		HubLayout l = HubLayout.fallback();
		assertFalse(l.steps().isEmpty());
		assertEquals(Leaderboards.MATCH_WINS, l.boards().getFirst().objective());
		double[] spawn = l.spawnWorld();
		assertEquals(65.0, spawn[1], "players stand on the floor (layout y 0 at world y 64)");
		assertNotEquals(l.exitPortal(), l.matchTerminal());
	}
}
