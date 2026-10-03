package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class StatusReporterTest {
	/** Plan retest3 section 1.1: status gains pause (the Night City pause screen is open). */
	@Test
	void statusCarriesPause() {
		JsonObject paused = JsonParser.parseString(StatusReporter.format(18.5F, 20.0F, 17, "survival", true, true)).getAsJsonObject();
		assertEquals("status", paused.get("t").getAsString());
		assertEquals(18.5, paused.get("hp").getAsDouble());
		assertEquals(20.0, paused.get("maxHp").getAsDouble());
		assertEquals(17, paused.get("food").getAsInt());
		assertEquals("survival", paused.get("gm").getAsString());
		assertTrue(paused.get("screen").getAsBoolean());
		assertTrue(paused.get("pause").getAsJsonPrimitive().isBoolean());
		assertTrue(paused.get("pause").getAsBoolean());
		assertFalse(JsonParser.parseString(StatusReporter.format(20.0F, 20.0F, 20, "creative", false, false)).getAsJsonObject().get("pause").getAsBoolean());
	}

	@Test
	void nobodyHasPausedNightCityAtStart() {
		assertFalse(StatusReporter.nightCityPaused);
	}
}
