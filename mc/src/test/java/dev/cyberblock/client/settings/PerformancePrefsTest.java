package dev.cyberblock.client.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Megaplan 9-S1: the Performance tab's client-only values (frame scale 50..100 % by 5, figure cap 16..48 by 4) and their file. */
class PerformancePrefsTest {
	@Test
	void defaultsAreFullSizeAndFortyEightFigures() {
		assertEquals(new PerformancePrefs(100, 48), PerformancePrefs.DEFAULT);
		assertEquals(1.0, PerformancePrefs.DEFAULT.scaleFactor());
	}

	@Test
	void valuesAreClampedAndSnappedToTheirSteps() {
		assertEquals(new PerformancePrefs(50, 16), new PerformancePrefs(10, 2));
		assertEquals(new PerformancePrefs(100, 48), new PerformancePrefs(400, 999));
		PerformancePrefs p = new PerformancePrefs(73, 31);
		assertEquals(70, p.scale(), "snapped down to a step of 5");
		assertEquals(28, p.figureCap(), "snapped down to a step of 4");
		assertEquals(0.7, p.scaleFactor(), 1e-9);
	}

	@Test
	void theFileRoundTripsAndBadValuesKeepTheirDefaults() {
		PerformancePrefs p = new PerformancePrefs(75, 24);
		assertEquals(JsonParser.parseString("{'scale':75,'figureCap':24}"), p.toJson());
		assertEquals(p, PerformancePrefs.fromJson(p.toJson()));
		assertEquals(PerformancePrefs.DEFAULT, PerformancePrefs.fromJson(JsonParser.parseString("{'scale':'small','figureCap':true}").getAsJsonObject()));
		assertEquals(new PerformancePrefs(50, 48), PerformancePrefs.fromJson(JsonParser.parseString("{'scale':-3}").getAsJsonObject()));
	}

	@Test
	void eachValueChangesAlone() {
		PerformancePrefs p = PerformancePrefs.DEFAULT.withScale(60).withFigureCap(20);
		assertEquals(new PerformancePrefs(60, 20), p);
	}
}
