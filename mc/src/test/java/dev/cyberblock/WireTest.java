package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Every Minecraft to Cyberpunk message is one valid JSON object, whatever numbers go in. */
class WireTest {
	private static JsonObject json(final String line) {
		assertFalse(line.contains("\n"), "one line");
		return JsonParser.parseString(line).getAsJsonObject();
	}

	@Test
	void hitMatchesTheProtocolExample() {
		assertEquals("{\"t\":\"hit\",\"target\":\"9007199254740993\",\"dmg\":7.000,\"kind\":\"melee\",\"impulse\":[0.000,1.500,6.000]}",
			Wire.hit("9007199254740993", 7.0, "melee", 0.0, 1.5, 6.0));
	}

	@Test
	void igniteMatchesTheProtocolExample() {
		assertEquals("{\"t\":\"ignite\",\"target\":\"9007199254740993\",\"seconds\":8.00}", Wire.ignite("9007199254740993", 8.0));
	}

	@Test
	void explosionMatchesTheProtocolExample() {
		assertEquals("{\"t\":\"explosion\",\"pos\":[10.500,4.000,-20.500],\"power\":4.000,\"fire\":false}", Wire.explosion(10.5, 4.0, -20.5, 4.0, false));
	}

	@Test
	void nonFiniteNumbersNeverMakeInvalidJson() {
		JsonObject hit = json(Wire.hit("1", Double.NaN, "arrow", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN));
		assertEquals(0.0, hit.get("dmg").getAsDouble());
		assertEquals(0.0, hit.getAsJsonArray("impulse").get(0).getAsDouble());
		assertEquals(0.0, json(Wire.ignite("1", Double.NaN)).get("seconds").getAsDouble());
		JsonObject boom = json(Wire.explosion(Double.NaN, Double.POSITIVE_INFINITY, 0.0, Double.NaN, true));
		assertEquals(0.0, boom.getAsJsonArray("pos").get(1).getAsDouble());
		assertEquals(0.0, boom.get("power").getAsDouble());
		assertTrue(boom.get("fire").getAsBoolean());
	}

	@Test
	void theLargestIdSurvivesAsAString() {
		JsonObject hit = json(Wire.hit(Long.toUnsignedString(-1L), 1.0, "mob", 0.0, 0.0, 0.0));
		assertEquals("18446744073709551615", hit.get("target").getAsString());
	}

	/** Plan retest2 section 1.2: {"t":"align","k":57}. */
	@Test
	void alignMatchesTheProtocolExample() {
		assertEquals("{\"t\":\"align\",\"k\":57}", Wire.align(57));
		assertEquals(0, json(Wire.align(0)).get("k").getAsInt());
		assertEquals(63, json(Wire.align(63)).get("k").getAsInt());
	}

	@Test
	void numbersUseADotWhateverTheLocale() {
		java.util.Locale old = java.util.Locale.getDefault();
		try {
			java.util.Locale.setDefault(java.util.Locale.GERMANY);
			assertEquals(1.25, json(Wire.ignite("1", 1.25)).get("seconds").getAsDouble());
			assertEquals(3.5, json(Wire.explosion(0.0, 0.0, 0.0, 3.5, false)).get("power").getAsDouble());
		} finally {
			java.util.Locale.setDefault(old);
		}
	}
}
