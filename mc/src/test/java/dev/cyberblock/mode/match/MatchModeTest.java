package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1 and section 1.7: the match_mode content kind, in package 8A's schema (shared/content-schemas/match_mode.schema.json). */
class MatchModeTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "test");
	/** 8A's gang_wars default, as shipped. */
	static final String GANG_WARS = """
		{"schema": 1, "name": "Gang Wars", "description": "Teams stand for gangs and fight over the area's capture points.",
		 "teams": {"min": 2, "max": 4}, "team_size": {"min": 2, "max": 8}, "time_limit_s": 1800, "score_limit": 300,
		 "scoring": {"kill": 2, "capture": 1}, "respawn_s": 12, "friendly_fire": false, "pvp": true, "zone": {"min_radius": 96, "max_radius": 320},
		 "capture_points": {"count": 3, "radius": 8, "seconds": 20}, "districts": true, "reputation": true, "heat_scale": 1.0,
		 "roles": ["teammate", "hacker", "spectator", "overlord"],
		 "team_palette": [{"name": "Tyger Claws", "colour": "#d4372c", "faction": "tygerClaws"}, {"name": "Valentinos", "colour": "#e3b02a", "faction": "valentinos"},
		  {"name": "Maelstrom", "colour": "#c2c7d4", "faction": "maelstrom"}, {"name": "6th Street", "colour": "#3a9d5d", "faction": "6thStreet"}]}""";
	/** 8A's free_for_all default, as shipped (shortened palette text only). */
	static final String FREE_FOR_ALL = """
		{"schema": 1, "name": "Free For All", "description": "Everyone alone, everyone a target.", "teams": {"min": 2, "max": 8},
		 "team_size": {"min": 1, "max": 1}, "time_limit_s": 600, "score_limit": 20, "scoring": {"kill": 1, "capture": 0}, "respawn_s": 6,
		 "friendly_fire": true, "pvp": true, "zone": {"min_radius": 24, "max_radius": 128}, "districts": false, "reputation": false, "heat_scale": 0.5,
		 "roles": ["spectator", "overlord"],
		 "team_palette": [{"name": "Red", "colour": "#e5384f"}, {"name": "Blue", "colour": "#3a7bff"}, {"name": "Green", "colour": "#35d07f"},
		  {"name": "Yellow", "colour": "#f2c230"}, {"name": "Cyan", "colour": "#2fe0ff"}, {"name": "Magenta", "colour": "#ff2fb3"},
		  {"name": "Orange", "colour": "#ff8a1f"}, {"name": "White", "colour": "#e8eef5"}]}""";

	private static MatchMode parse(final String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		return MatchMode.parse(ID, o);
	}

	/** GANG_WARS with one field replaced (a JSON value) or removed (null). */
	private static String with(final String key, final String value) {
		JsonObject o = JsonParser.parseString(GANG_WARS).getAsJsonObject();
		if (value == null) {
			o.remove(key);
		} else {
			o.add(key, JsonParser.parseString(value));
		}

		return o.toString();
	}

	@Test
	void gangWarsReadsEveryField() {
		MatchMode m = parse(GANG_WARS);
		assertEquals("Gang Wars", m.name());
		assertEquals(2, m.minTeams());
		assertEquals(4, m.teams().size(), "teams.max palette entries");
		assertEquals(new MatchMode.TeamSpec("6th Street", 0x3A9D5D, "6thStreet"), m.teams().get(3), "faction is the gang");
		assertEquals(2, m.teamSizeMin());
		assertEquals(8, m.teamSizeMax());
		assertEquals(4, m.minPlayers(), "team_size.min on each of teams.min");
		assertEquals(1800, m.timeLimit());
		assertEquals(300, m.scoreLimit());
		assertEquals(2, m.killScore());
		assertEquals(1, m.captureScore());
		assertEquals(12, m.respawnS());
		assertFalse(m.friendlyFire());
		assertTrue(m.pvp());
		assertEquals(96, m.zoneMin());
		assertEquals(320, m.zoneMax());
		assertTrue(m.capture());
		assertEquals("capture", m.scoringKey());
		assertEquals(3, m.captureCount());
		assertEquals(8, m.captureRadius());
		assertEquals(20, m.captureSeconds());
		assertTrue(m.districts());
		assertTrue(m.reputation());
		assertEquals(1.0, m.heatScale(), 1e-9);
		assertEquals(Set.of(Match.Role.values()), m.roles());
	}

	@Test
	void freeForAllIsManyTeamsOfOneAndAlwaysHasPlayers() {
		MatchMode m = parse(FREE_FOR_ALL);
		assertEquals(8, m.teams().size());
		assertEquals(1, m.teamSizeMax());
		assertFalse(m.capture());
		assertEquals("kills", m.scoringKey());
		assertTrue(m.allows(Match.Role.TEAMMATE), "players fight even though the mode lists no teammate role");
		assertTrue(m.allows(Match.Role.SPECTATOR));
		assertFalse(m.allows(Match.Role.HACKER), "a role the mode does not list");
	}

	@Test
	void theZoneRangeClampsTheCreatorsRadius() {
		MatchMode m = parse(GANG_WARS);
		assertEquals(96, m.clampRadius(16));
		assertEquals(320, m.clampRadius(1000));
		assertEquals(200, m.clampRadius(200));
		assertEquals(96, m.defaultRadius());
	}

	@Test
	void unknownFactionsMeanNoGangAndPaletteIsCutAtTeamsMax() {
		MatchMode m = parse(with("teams", "{\"min\":2,\"max\":2}").replace("tygerClaws", "tyger_claws"));
		assertEquals(2, m.teams().size());
		assertEquals("", m.teams().get(0).gang());
		assertEquals("valentinos", m.teams().get(1).gang());
	}

	@Test
	void badFilesAreRefusedWithAReason() {
		String[] bad = {
			"{}",
			with("name", "\"\""),
			with("name", "\"" + "x".repeat(49) + "\""),
			with("description", null),
			with("teams", "[{\"name\":\"A\",\"colour\":\"#000000\"}]"),
			with("teams", "{\"min\":3,\"max\":2}"),
			with("teams", "{\"min\":1,\"max\":2}"),
			with("team_size", "{\"min\":1,\"max\":17}"),
			with("teams", "{\"min\":5,\"max\":8}"),
			with("scoring", "\"kills\""),
			with("scoring", "{\"kill\":1}"),
			with("time_limit_s", "30"),
			with("time_limit_s", "600.5"),
			with("score_limit", "0"),
			with("zone", "{\"min_radius\":8,\"max_radius\":64}"),
			with("capture_points", "{\"count\":10,\"radius\":8,\"seconds\":20}"),
			with("pvp", "\"yes\""),
			with("heat_scale", "3"),
			with("roles", "[]"),
			with("roles", "[\"teammate\",\"teammate\"]"),
			with("roles", "[\"pilot\"]"),
			with("team_palette", "[{\"name\":\"A\",\"colour\":\"red\"},{\"name\":\"B\",\"colour\":\"#000000\"}]"),
			with("team_palette", "[{\"name\":\"A\",\"colour\":\"#000000\"}]"),
		};
		for (String json : bad) {
			assertThrows(IllegalArgumentException.class, () -> parse(json), json);
		}
	}

	@Test
	void noCapturePointsMeansAKillsMode() {
		MatchMode m = parse(with("capture_points", null));
		assertFalse(m.capture());
		assertEquals(0, m.captureCount());
	}

	/** Every match_mode file the mod ships (8A's four defaults once merged) loads: the loader reads what content_check validates. */
	@Test
	void theShippedDefaultsLoad() throws IOException {
		int found = 0;
		for (String name : new String[] {"gang_wars", "team_deathmatch", "free_for_all", "last_team_standing"}) {
			try (InputStream in = MatchModeTest.class.getResourceAsStream("/data/cyberblock/cyberblock/match_mode/" + name + ".json")) {
				if (in == null) {
					continue;
				}

				found++;
				MatchMode m = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				assertTrue(m.teams().size() >= m.minTeams(), name);
				assertTrue(m.minPlayers() >= 1, name);
			}
		}

		Assumptions.assumeTrue(found > 0, "8A's match_mode defaults are not on this branch yet (they arrive with the Phase 8 merge)");
		assertEquals(4, found, "all four of 8A's defaults");
	}

	@Test
	void builtInModesAlwaysExist() {
		assertTrue(MatchMode.BUILT_IN.containsKey("cyberblock:team_deathmatch"));
		MatchMode gw = MatchMode.BUILT_IN.get("cyberblock:gang_wars");
		assertTrue(gw.capture());
		assertTrue(gw.reputation());
		assertTrue(gw.teams().stream().allMatch(t -> MatchMode.GANGS.contains(t.gang())), "the built-in gangs are real attitude groups");
		assertEquals(parse(GANG_WARS).teams(), gw.teams(), "the same teams as 8A's gang_wars");
	}
}
