package dev.cyberblock.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: the Afterlife's dimension files (its own type, always day, no mob spawns) and the hub's saved data. */
class AfterlifeDimensionTest {
	private static JsonObject resource(final String path) throws IOException {
		try (Reader r = new InputStreamReader(AfterlifeDimensionTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		}
	}

	@Test
	void theDimensionUsesItsOwnTypeAndTheVoid() throws IOException {
		JsonObject dim = resource("/data/cyberblock/dimension/afterlife.json");
		assertEquals("cyberblock:afterlife", dim.get("type").getAsString());
		JsonObject settings = dim.getAsJsonObject("generator").getAsJsonObject("settings");
		assertEquals("minecraft:the_void", settings.get("biome").getAsString(), "the void biome spawns no mobs");
		assertTrue(settings.getAsJsonArray("layers").isEmpty(), "nothing generated: the hub is built from the layout");
		assertFalse(settings.get("features").getAsBoolean());
	}

	@Test
	void theTypeIsAlwaysDayWithNoMonsters() throws IOException {
		JsonObject type = resource("/data/cyberblock/dimension_type/afterlife.json");
		assertTrue(type.get("has_fixed_time").getAsBoolean());
		assertEquals("#minecraft:universal", type.get("timelines").getAsString(), "no day or moon timeline: the sky stays at its day values");
		assertEquals(0, type.get("monster_spawn_light_level").getAsInt());
		assertEquals(0, type.get("monster_spawn_block_light_limit").getAsInt());
		assertTrue(type.get("has_skylight").getAsBoolean());
	}

	@Test
	void whereAPlayerCameFromSurvivesASave() {
		HubData.Return r = new HubData.Return("minecraft:overworld", -1230.5, 64.0, 820.25, 90.0F, -10.0F, true);
		var json = HubData.Return.CODEC.encodeStart(JsonOps.INSTANCE, r).getOrThrow();
		assertEquals(r, HubData.Return.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		var old = JsonParser.parseString("{\"level\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"yaw\":0,\"pitch\":0}");
		assertFalse(HubData.Return.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().noGravity(), "absent no_gravity = false");
		var data = HubData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();
		assertEquals("", data.built(), "not built yet");
		assertNull(data.spawn());
		UUID who = UUID.randomUUID();
		data.setReturn(who, r);
		var saved = HubData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
		assertEquals(r, HubData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().takeReturn(who));
	}
}
