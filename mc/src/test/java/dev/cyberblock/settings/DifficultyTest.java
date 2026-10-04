package dev.cyberblock.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import dev.cyberblock.mode.overlord.Intensity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Megaplan 9-S1: the Difficulty tab's world values (Overlord intensity default, Heat scaling), their checks and their save format. */
class DifficultyTest {
	@AfterEach
	void forget() {
		Difficulty.use(new Difficulty.Data());
	}

	@Test
	void defaultsAreNormalAndTheShippedHeat() {
		Difficulty.use(new Difficulty.Data());
		assertSame(Intensity.NORMAL, Difficulty.overlordDefault());
		assertEquals(100, Difficulty.heatPercent());
		assertEquals(1.0, Difficulty.heatScale());
	}

	@Test
	void changesAreSavedAndReported() {
		Difficulty.Data data = new Difficulty.Data();
		Difficulty.use(data);
		assertFalse(Difficulty.setOverlord(Intensity.NORMAL), "the same value is no change");
		assertFalse(data.isDirty());
		assertTrue(Difficulty.setOverlord(Intensity.BRUTAL));
		assertTrue(Difficulty.setHeat(150));
		assertTrue(data.isDirty());
		assertSame(Intensity.BRUTAL, Difficulty.overlordDefault());
		assertEquals(1.5, Difficulty.heatScale());
		assertEquals("Overlord intensity default brutal, Heat scaling 150 %", Difficulty.describe());
	}

	@Test
	void heatOutsideItsRangeIsRefused() {
		Difficulty.use(new Difficulty.Data());
		assertFalse(Difficulty.setHeat(24));
		assertFalse(Difficulty.setHeat(201));
		assertFalse(Difficulty.setHeat(-100));
		assertEquals(100, Difficulty.heatPercent());
		assertTrue(Difficulty.setHeat(25));
		assertTrue(Difficulty.setHeat(200));
	}

	@Test
	void theSaveRoundTripsAndABadSaveReadsAsTheDefaults() {
		Difficulty.Data data = new Difficulty.Data("chill", 75);
		var encoded = Difficulty.Data.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
		assertEquals(JsonParser.parseString("{'overlord':'chill','heat':75}"), encoded);
		Difficulty.Data back = Difficulty.Data.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		assertSame(Intensity.CHILL, back.overlord);
		assertEquals(75, back.heat);

		Difficulty.Data bad = Difficulty.Data.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{'overlord':'insane','heat':9000}")).getOrThrow();
		assertSame(Intensity.NORMAL, bad.overlord);
		assertEquals(100, bad.heat);
		Difficulty.Data empty = Difficulty.Data.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();
		assertSame(Intensity.NORMAL, empty.overlord);
		assertEquals(100, empty.heat);
	}

	@Test
	void aWorldsValuesAreUsedOnceAttached() {
		Difficulty.use(new Difficulty.Data("brutal", 50));
		assertSame(Intensity.BRUTAL, Difficulty.overlordDefault());
		assertEquals(0.5, Difficulty.heatScale());
	}
}
