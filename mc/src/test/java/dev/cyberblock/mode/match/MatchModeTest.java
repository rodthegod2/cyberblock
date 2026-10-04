package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1 and section 1.7: the match_mode content kind. */
class MatchModeTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "test");

	private static MatchMode parse(final String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		return MatchMode.parse(ID, o);
	}

	@Test
	void fullFile() {
		MatchMode m = parse("{\"schema\":1,\"name\":\"Gang Wars: Watson\",\"description\":\"Hold the corners.\",\"teams\":["
			+ "{\"name\":\"Claws\",\"colour\":\"#E040A0\",\"gang\":\"tygerClaws\"},{\"name\":\"Maelstrom\",\"color\":\"#d02020\",\"gang\":\"maelstrom\"},"
			+ "{\"name\":\"Valentinos\",\"colour\":\"#F0C020\",\"gang\":\"valentinos\"}],\"scoring\":\"capture\",\"pvp\":true,\"friendlyFire\":true,"
			+ "\"timeLimit\":900,\"scoreLimit\":500,\"capturePoints\":4,\"captureSeconds\":12,\"zoneRadius\":160,\"minPlayers\":3,\"extra\":42}");
		assertEquals("Gang Wars: Watson", m.name());
		assertEquals(3, m.teams().size());
		assertEquals(new MatchMode.TeamSpec("Maelstrom", 0xD02020, "maelstrom"), m.teams().get(1), "color is read as colour");
		assertTrue(m.capture());
		assertTrue(m.friendlyFire());
		assertEquals(900, m.timeLimit());
		assertEquals(500, m.scoreLimit());
		assertEquals(4, m.capturePoints());
		assertEquals(12, m.captureSeconds());
		assertEquals(160, m.zoneRadius());
		assertEquals(3, m.minPlayers());
	}

	@Test
	void defaults() {
		MatchMode m = parse("{\"schema\":1,\"name\":\"Brawl\"}");
		assertEquals(2, m.teams().size());
		assertEquals(MatchMode.Scoring.KILLS, m.scoring());
		assertTrue(m.pvp());
		assertFalse(m.friendlyFire());
		assertEquals(600, m.timeLimit());
		assertEquals(0, m.scoreLimit());
		assertEquals(0, m.capturePoints(), "kills modes have no capture points");
		assertEquals(3, parse("{\"name\":\"Wars\",\"scoring\":\"capture\"}").capturePoints(), "capture modes have three");
		assertEquals("", m.description());
	}

	@Test
	void anUnknownGangMeansNoGang() {
		MatchMode m = parse("{\"name\":\"X\",\"teams\":[{\"name\":\"A\",\"colour\":\"#000000\",\"gang\":\"tyger_claws\"},{\"name\":\"B\",\"colour\":\"#FFFFFF\"}]}");
		assertEquals("", m.teams().get(0).gang());
		assertEquals("", m.teams().get(1).gang());
	}

	@Test
	void badFilesAreRefusedWithAReason() {
		String[] bad = {
			"{}",
			"{\"name\":\"\"}",
			"{\"name\":\"X\",\"teams\":[{\"name\":\"A\",\"colour\":\"#000000\"}]}",
			"{\"name\":\"X\",\"teams\":[{\"name\":\"A\",\"colour\":\"red\"},{\"name\":\"B\",\"colour\":\"#000000\"}]}",
			"{\"name\":\"X\",\"teams\":\"two\"}",
			"{\"name\":\"X\",\"scoring\":\"flags\"}",
			"{\"name\":\"X\",\"timeLimit\":30}",
			"{\"name\":\"X\",\"timeLimit\":600.5}",
			"{\"name\":\"X\",\"zoneRadius\":2000}",
			"{\"name\":\"X\",\"pvp\":\"yes\"}",
			"{\"name\":\"" + "x".repeat(49) + "\"}",
		};
		for (String json : bad) {
			assertThrows(IllegalArgumentException.class, () -> parse(json), json);
		}
	}

	@Test
	void builtInModesAlwaysExist() {
		assertTrue(MatchMode.BUILT_IN.containsKey("cyberblock:team_deathmatch"));
		MatchMode gw = MatchMode.BUILT_IN.get("cyberblock:gang_wars");
		assertTrue(gw.capture());
		assertTrue(gw.teams().stream().allMatch(t -> MatchMode.GANGS.contains(t.gang())), "the built-in gangs are real attitude groups");
	}
}
