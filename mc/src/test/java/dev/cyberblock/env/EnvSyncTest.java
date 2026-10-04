package dev.cyberblock.env;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.env.EnvSync.Weather;
import org.junit.jupiter.api.Test;

/** Plan 3-E1: Night City's clock and weather as Minecraft day time and weather. */
class EnvSyncTest {
	@Test
	void dayTicksFollowThePlanFormula() {
		// ticks = ((h*60 + m) / 1440 * 24000 + 18000) mod 24000: Minecraft's day starts at 06:00
		assertEquals(0, EnvSync.dayTicks(6, 0));
		assertEquals(6000, EnvSync.dayTicks(12, 0));
		assertEquals(12000, EnvSync.dayTicks(18, 0));
		assertEquals(18000, EnvSync.dayTicks(0, 0));
		assertEquals(17983, EnvSync.dayTicks(23, 59));
		assertEquals(23983, EnvSync.dayTicks(5, 59));
		assertEquals(250, EnvSync.dayTicks(6, 15));
	}

	@Test
	void everyMinuteOfTheDayIsInRangeAndInOrder() {
		int last = -1;
		for (int minute = 6 * 60; minute < 6 * 60 + 1440; minute++) {
			int ticks = EnvSync.dayTicks(minute / 60 % 24, minute % 60);
			assertTrue(ticks >= 0 && ticks < 24000, "minute " + minute);
			assertTrue(ticks > last, "minute " + minute);
			last = ticks;
		}
	}

	@Test
	void theClockKeepsItsDayCount() {
		// day 5 at noon, Night City says 13:00: same day, an hour on
		assertEquals(5 * 24000 + 7000, EnvSync.nextTotal(5 * 24000 + 6000, 7000));
		// Night City went back a little (a quest skip of minutes): back on the same day
		assertEquals(5 * 24000 + 5000, EnvSync.nextTotal(5 * 24000 + 6000, 5000));
		// 05:59 -> 06:00 crosses Minecraft's day boundary forward, not back a whole day
		assertEquals(6 * 24000 + 10, EnvSync.nextTotal(5 * 24000 + 23990, 10));
		// and a step back over it goes back to the day before
		assertEquals(5 * 24000 + 23990, EnvSync.nextTotal(6 * 24000 + 10, 23990));
		assertEquals(18000, EnvSync.nextTotal(0, 18000));
	}

	@Test
	void weatherMapsRainStormAndTheRestAsClear() {
		assertEquals(Weather.RAIN, EnvSync.weather(JsonParser.parseString("\"rain\"")));
		assertEquals(Weather.THUNDER, EnvSync.weather(JsonParser.parseString("\"storm\"")));
		for (String clear : new String[] {"clear", "cloudy", "fog", "smog", "sandstorm"}) {
			assertEquals(Weather.CLEAR, EnvSync.weather(JsonParser.parseString("\"" + clear + "\"")), clear);
		}
	}

	@Test
	void unreadableWeatherKeepsTheCurrentWeather() {
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("\"other\"")));
		assertEquals(Weather.KEEP, EnvSync.weather(null));
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("null")));
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("3")));
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("\"RAIN\"")));
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("\"hail\"")));
		assertEquals(Weather.KEEP, EnvSync.weather(JsonParser.parseString("[\"rain\"]")));
	}

	@Test
	void envTimeIsHourAndMinuteOrNothing() {
		assertArrayEquals(new int[] {21, 5}, EnvSync.time(JsonParser.parseString("{\"t\":\"env\",\"time\":[21,5]}").getAsJsonObject()));
		for (String bad : new String[] {"{}", "{\"time\":[24,0]}", "{\"time\":[0,60]}", "{\"time\":[1.5,0]}", "{\"time\":[1]}",
			"{\"time\":\"12:00\"}", "{\"time\":[-1,0]}", "{\"time\":[\"1\",\"2\"]}", "{\"time\":null}"}) {
			assertNull(EnvSync.time(JsonParser.parseString(bad).getAsJsonObject()), bad);
		}
	}

	@Test
	void thePathLineSaysWhereTheWeatherComesFrom() {
		assertEquals("env: weather from host CbHost1 (rain)", EnvSync.pathLine("CbHost1", "rain", Weather.RAIN, null));
		assertEquals("env: weather from host CbHost1 (cloudy)", EnvSync.pathLine("CbHost1", "cloudy", Weather.CLEAR, null));
		assertEquals("env: weather from host CbHost1 (storm): rain set, not drawn (biome minecraft:the_void has no precipitation)",
			EnvSync.pathLine("CbHost1", "storm", Weather.THUNDER, "minecraft:the_void"));
		assertEquals("env: time only (weather unreadable)", EnvSync.pathLine("CbHost1", "other", Weather.KEEP, null));
		assertEquals("env: time only (weather unreadable)", EnvSync.pathLine("CbHost1", null, Weather.KEEP, null));
	}
}
