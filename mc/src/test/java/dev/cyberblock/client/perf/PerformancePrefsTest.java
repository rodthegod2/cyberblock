package dev.cyberblock.client.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: Performance mode's two values are always in range, and survive the config file. */
class PerformancePrefsTest {
	@Test
	void defaultsAreFullQuality() {
		assertEquals(1.0, PerformancePrefs.DEFAULT.scale());
		assertEquals(48, PerformancePrefs.DEFAULT.figureCap());
		assertFalse(PerformancePrefs.DEFAULT.on());
		assertTrue(PerformancePrefs.DEFAULT.withScale(0.75).on() && PerformancePrefs.DEFAULT.withFigureCap(32).on());
	}

	@Test
	void valuesAreClampedAndRounded() {
		assertEquals(new PerformancePrefs(0.5, 16), new PerformancePrefs(0.1, 2));
		assertEquals(new PerformancePrefs(1.0, 48), new PerformancePrefs(3.0, 300));
		assertEquals(1.0, new PerformancePrefs(Double.NaN, 32).scale());
		assertEquals(0.73, new PerformancePrefs(0.7300000001, 32).scale());
	}

	@Test
	void theFileRoundTripsAndABadValueKeepsItsDefault() {
		PerformancePrefs p = new PerformancePrefs(0.75, 24);
		assertEquals(p, PerformancePrefs.fromJson(p.toJson()));
		assertEquals("{\"scale\":0.75,\"figureCap\":24}", p.toJson().toString());
		assertEquals(new PerformancePrefs(1.0, 20), PerformancePrefs.fromJson(JsonParser.parseString("{'scale':'half','figureCap':20.4}").getAsJsonObject()));
		assertEquals(PerformancePrefs.DEFAULT, PerformancePrefs.fromJson(JsonParser.parseString("{}").getAsJsonObject()));
		assertEquals(new PerformancePrefs(0.5, 48), PerformancePrefs.fromJson(JsonParser.parseString("{'scale':0.2,'figureCap':1e12}").getAsJsonObject()));
	}
}
