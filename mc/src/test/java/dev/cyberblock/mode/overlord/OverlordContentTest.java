package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Megaplan 8-O1 / section 1.7: the spawn_palette and event kinds in 8A's shapes, and the console recipe. */
class OverlordContentTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "test");

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static final String MC = "{\"schema\":1,\"name\":\"Zombie pack\",\"description\":\"Shamblers.\",\"group\":\"monster\",\"kind\":\"minecraft\","
		+ "\"cost\":6,\"count\":3,\"hostile\":true,\"mob\":\"minecraft:zombie\"}";
	private static final String NC = "{\"schema\":1,\"name\":\"Tyger Claws\",\"description\":\"Blades out.\",\"group\":\"gang\",\"kind\":\"night_city\","
		+ "\"cost\":14,\"count\":3,\"hostile\":true,\"cooldown_s\":20,\"rec\":\"ganger\",\"faction\":\"tygerClaws\",\"heavy\":false,"
		+ "\"squad\":{\"mob\":\"minecraft:vindicator\",\"count\":2}}";

	@Test
	void aMinecraftEntry() {
		OverlordContent.SpawnEntry e = OverlordContent.parseSpawn(ID, json(MC));
		assertFalse(e.nightCity());
		assertEquals(6, e.cost());
		assertEquals(3, e.count());
		assertEquals(Identifier.parse("minecraft:zombie"), e.squadMob(), "a Minecraft entry is its own squad");
		assertEquals(3, e.squadCount());
		assertNull(e.rec());
		assertEquals(0, e.cooldownS());
	}

	@Test
	void aNightCityEntryWithItsMinecraftStandIn() {
		OverlordContent.SpawnEntry e = OverlordContent.parseSpawn(ID, json(NC));
		assertTrue(e.nightCity());
		assertEquals("ganger", e.rec());
		assertEquals("tygerClaws", e.faction());
		assertEquals(20, e.cooldownS());
		assertEquals(Identifier.parse("minecraft:vindicator"), e.squadMob());
		assertEquals(2, e.squadCount());
	}

	@Test
	void badPaletteEntriesAreSkipped() {
		String[] bad = {
			MC.replace("\"cost\":6", "\"cost\":0"),
			MC.replace("\"count\":3", "\"count\":9"),
			MC.replace("\"group\":\"monster\"", "\"group\":\"robots\""),
			MC.replace(",\"mob\":\"minecraft:zombie\"", ""),
			MC.replace("}", ",\"rec\":\"ganger\"}"),
			MC.replace("}", ",\"extra\":1}"),
			NC.replace("\"rec\":\"ganger\"", "\"rec\":\"turret\""),
			NC.replace(",\"squad\":{\"mob\":\"minecraft:vindicator\",\"count\":2}", ""),
			NC.replace("}}", "},\"mob\":\"minecraft:zombie\"}"),
			NC.replace("tygerClaws", "tyger claws"),
			MC.replace("\"cost\":6", "\"cost\":6.5"),
		};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> OverlordContent.parseSpawn(ID, json(s)), s);
		}
	}

	private static String event(final String effect) {
		return "{\"schema\":1,\"name\":\"E\",\"description\":\"d\",\"cost\":30,\"warning_s\":5,\"cooldown_s\":60,\"radius\":24,"
			+ "\"scales_with_intensity\":true,\"night_city\":true,\"effect\":" + effect + "}";
	}

	@Test
	void everyEventShape() {
		assertEquals(20, OverlordContent.parseEvent(ID, json(event("{\"type\":\"blackout\",\"seconds\":20}"))).effect().seconds());
		OverlordContent.Effect emp = OverlordContent.parseEvent(ID, json(event("{\"type\":\"emp\",\"seconds\":8,\"cyberware_off\":true}"))).effect();
		assertTrue(emp.cyberwareOff());
		OverlordContent.Effect air = OverlordContent.parseEvent(ID, json(event(
			"{\"type\":\"airstrike\",\"strikes\":5,\"spread\":10,\"power\":2.5,\"interval_ticks\":8}"))).effect();
		assertEquals(5, air.strikes());
		assertEquals(2.5, air.power());
		assertEquals(30, OverlordContent.parseEvent(ID, json(event("{\"type\":\"traffic_jam\",\"seconds\":30}"))).effect().seconds());
		OverlordContent.Effect drop = OverlordContent.parseEvent(ID, json(event(
			"{\"type\":\"supply_drop\",\"eddies\":150,\"items\":[{\"item\":\"minecraft:golden_apple\",\"count\":2}]}"))).effect();
		assertEquals(150, drop.eddies());
		assertEquals(new OverlordContent.Stack(Identifier.parse("minecraft:golden_apple"), 2), drop.items().getFirst());
		assertEquals("thunder", OverlordContent.parseEvent(ID, json(event("{\"type\":\"weather\",\"weather\":\"thunder\",\"seconds\":60}"))).effect()
			.weather());
		OverlordContent.EventEntry outbreak = OverlordContent.parseEvent(ID, json(event("{\"type\":\"outbreak\",\"spawn\":\"cyberblock:psycho\",\"count\":2}")));
		assertEquals(Identifier.parse("cyberblock:psycho"), outbreak.effect().spawn());
		assertEquals(5, outbreak.warningS());
		assertTrue(outbreak.scales());
		assertTrue(outbreak.nightCity());
	}

	@Test
	void badEventsAreSkipped() {
		String[] bad = {
			event("{\"type\":\"blackout\",\"seconds\":2}"),
			event("{\"type\":\"nuke\",\"seconds\":20}"),
			event("{\"type\":\"blackout\",\"seconds\":20,\"cyberware_off\":true}"),
			event("{\"type\":\"airstrike\",\"strikes\":5,\"spread\":10,\"power\":9,\"interval_ticks\":8}"),
			event("{\"type\":\"weather\",\"weather\":\"snow\",\"seconds\":60}"),
			event("{\"type\":\"outbreak\",\"spawn\":\"not an id\",\"count\":2}"),
			event("{\"type\":\"blackout\",\"seconds\":20}").replace("\"warning_s\":5", "\"warning_s\":1"),
			event("{\"type\":\"blackout\",\"seconds\":20}").replace(",\"night_city\":true", ""),
		};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> OverlordContent.parseEvent(ID, json(s)), s);
		}
	}

	@Test
	void theWarningIsNeverUnderThreeSeconds() {
		// the schema floor and the code floor agree; Overlord queues max(warning_s * 20, WARNING_TICKS)
		assertThrows(IllegalArgumentException.class, () -> OverlordContent.parseEvent(ID, json(event("{\"type\":\"blackout\",\"seconds\":20}")
			.replace("\"warning_s\":5", "\"warning_s\":2"))));
		assertEquals(3 * 20, Fairness.WARNING_TICKS);
	}

	@Test
	void intensityScalesEventsThatSayTheyScale() {
		OverlordContent.EventEntry e = OverlordContent.parseEvent(ID, json(event("{\"type\":\"blackout\",\"seconds\":20}")));
		assertEquals(0.75, OverlordWorld.scale(e, Intensity.CHILL));
		assertEquals(1.5, OverlordWorld.scale(e, Intensity.BRUTAL));
		OverlordContent.EventEntry fixed = OverlordContent.parseEvent(ID, json(event("{\"type\":\"blackout\",\"seconds\":20}")
			.replace("\"scales_with_intensity\":true", "\"scales_with_intensity\":false")));
		assertEquals(1.0, OverlordWorld.scale(fixed, Intensity.BRUTAL));
		assertEquals(4, OverlordWorld.scaled(3, 1.5, 1, 4), "an outbreak never goes past 4 squads");
	}

	@Test
	void theWarningLineSaysWhichWay() {
		assertEquals("N", OverlordWorld.compass(0, -10));
		assertEquals("E", OverlordWorld.compass(10, 0));
		assertEquals("S", OverlordWorld.compass(0, 10));
		assertEquals("W", OverlordWorld.compass(-10, 0));
		assertEquals("NE", OverlordWorld.compass(10, -10));
	}

	/** 8A's shipped defaults (on main after the merge) all load, and every outbreak names a shipped palette entry. */
	@Test
	void everyShippedEntryLoads() throws java.io.IOException {
		Path root = Path.of("src/main/resources/data/cyberblock/cyberblock");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(root.resolve("spawn_palette")) && Files.isDirectory(root.resolve("event")),
			"8A's spawn_palette and event defaults are not on this branch");
		java.util.Set<Identifier> palette = new java.util.HashSet<>();
		try (var files = Files.list(root.resolve("spawn_palette"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				Identifier id = Identifier.fromNamespaceAndPath("cyberblock", f.getFileName().toString().replace(".json", ""));
				assertDoesNotThrow(() -> OverlordContent.parseSpawn(id, json(Files.readString(f))), f.toString());
				palette.add(id);
			}
		}

		try (var files = Files.list(root.resolve("event"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				OverlordContent.EventEntry e = assertDoesNotThrow(() -> OverlordContent.parseEvent(ID, json(Files.readString(f))), f.toString());
				if (e.effect().spawn() != null) {
					assertTrue(palette.contains(e.effect().spawn()), f + " names " + e.effect().spawn());
				}
			}
		}

		assertFalse(palette.isEmpty());
	}

	@Test
	void survivalPlayersCraftTheConsole() throws java.io.IOException {
		JsonObject recipe = json(Files.readString(Path.of("src/main/resources/data/cyberblock/recipe/overlord_console.json")));
		assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
		assertEquals("cyberblock:" + OverlordBlocks.CONSOLE_ID, recipe.getAsJsonObject("result").get("id").getAsString());
	}
}
