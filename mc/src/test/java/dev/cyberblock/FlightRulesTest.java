package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class FlightRulesTest {
	private static final long TICK = 50_000_000L;

	/** Feet falling at speed m/s, one sample per tick, starting at y 100. */
	private static FlightRules.Fall falling(final double speed, final int ticks) {
		FlightRules.Fall fall = new FlightRules.Fall();
		for (int i = 0; i < ticks; i++) {
			fall.sample(1_000_000_000L + i * TICK, 10.0, 100.0 - speed * i * 0.05, -4.0);
		}

		return fall;
	}

	@Test
	void jumpWhileFallingStartsAGlide() {
		FlightRules.Fall fall = falling(2.0, 4);
		assertEquals(2.0, fall.speed3(), 1e-9);
		assertTrue(FlightRules.start(true, true, fall.speed3(), fall.fastNanos()));
	}

	@Test
	void notWithoutElytraNorWithoutJumpNorStandingStill() {
		FlightRules.Fall fall = falling(2.0, 4);
		assertFalse(FlightRules.start(true, false, fall.speed3(), fall.fastNanos()));
		assertFalse(FlightRules.start(false, true, fall.speed3(), fall.fastNanos()));
		FlightRules.Fall slow = falling(0.9, 4);
		assertFalse(FlightRules.start(true, true, slow.speed3(), slow.fastNanos()));
		FlightRules.Fall rising = falling(-5.0, 4); // a jump going up
		assertFalse(FlightRules.start(true, true, rising.speed3(), rising.fastNanos()));
	}

	@Test
	void needsThreeTicksOfFalling() {
		FlightRules.Fall fall = falling(5.0, 3);
		assertEquals(0.0, fall.speed3());
		assertFalse(FlightRules.start(true, true, fall.speed3(), fall.fastNanos()));
	}

	@Test
	void aLongFastFallStartsByItself() {
		FlightRules.Fall short_ = falling(20.0, 10); // 9 intervals: 0.45 s
		assertFalse(FlightRules.start(false, true, short_.speed3(), short_.fastNanos()));
		FlightRules.Fall fall = falling(20.0, 11); // 0.5 s
		assertEquals(500_000_000L, fall.fastNanos());
		assertTrue(FlightRules.start(false, true, fall.speed3(), fall.fastNanos()));
		assertFalse(FlightRules.start(false, false, fall.speed3(), fall.fastNanos()));
		FlightRules.Fall slow = falling(14.0, 40);
		assertEquals(0L, slow.fastNanos());
	}

	@Test
	void aPauseInTheFastFallStartsTheClockAgain() {
		FlightRules.Fall fall = falling(20.0, 8);
		long t = 1_000_000_000L + 8 * TICK;
		fall.sample(t, 10.0, 100.0 - 20.0 * 7 * 0.05, -4.0); // the feet did not move for a tick
		assertEquals(0L, fall.fastNanos());
		fall.sample(t + TICK, 10.0, 100.0 - 20.0 * 8 * 0.05, -4.0);
		assertEquals(TICK, fall.fastNanos());
	}

	@Test
	void staleOrRepeatedSamplesAreIgnored() {
		FlightRules.Fall fall = falling(2.0, 4);
		fall.sample(1_000_000_000L, 0.0, 0.0, 0.0); // older than the last
		assertEquals(2.0, fall.speed3(), 1e-9);
	}

	@Test
	void velocityFromTheFeet() {
		FlightRules.Fall fall = new FlightRules.Fall();
		for (int i = 0; i < 4; i++) {
			fall.sample(i * TICK, i * 0.5, 100.0 - i * 0.25, 0.0);
		}

		double[] v = fall.velocity();
		assertEquals(10.0, v[0], 1e-9);
		assertEquals(-5.0, v[1], 1e-9);
		assertEquals(0.0, v[2], 1e-9);
		fall.reset();
		assertArrayEquals(new double[] {0.0, 0.0, 0.0}, fall.velocity());
	}

	@Test
	void touchdown() {
		assertFalse(FlightRules.touchdown(false, true));
		assertTrue(FlightRules.touchdown(true, true));
		assertTrue(FlightRules.touchdown(false, false));
	}

	@Test
	void speedIsCappedAt200() {
		double[] v = FlightRules.capSpeed(300.0, 0.0, -400.0);
		assertEquals(200.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9);
		assertEquals(-0.8, v[2] / 200.0, 1e-9);
		assertArrayEquals(new double[] {1.0, 2.0, 3.0}, FlightRules.capSpeed(1.0, 2.0, 3.0));
		assertArrayEquals(new double[] {0.0, 0.0, 0.0}, FlightRules.capSpeed(Double.NaN, 0.0, 0.0));
	}

	@Test
	void flightJson() {
		JsonObject m = JsonParser.parseString(Wire.flight(true, 1.5, 70.0, -3.25, 10.0, -2.0, 0.5, 45.0, 12.5)).getAsJsonObject();
		assertEquals("flight", m.get("t").getAsString());
		assertTrue(m.get("active").getAsBoolean());
		JsonArray p = m.getAsJsonArray("p");
		assertEquals(1.5, p.get(0).getAsDouble());
		assertEquals(70.0, p.get(1).getAsDouble());
		assertEquals(-3.25, p.get(2).getAsDouble());
		assertEquals(-2.0, m.getAsJsonArray("v").get(1).getAsDouble());
		assertEquals(45.0, m.get("yaw").getAsDouble());
		assertEquals(12.5, m.get("pitch").getAsDouble());
		JsonObject landed = JsonParser.parseString(Wire.flight(false, 1.0, 64.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0)).getAsJsonObject();
		assertFalse(landed.get("active").getAsBoolean());
		JsonObject fast = JsonParser.parseString(Wire.flight(true, 0.0, 0.0, 0.0, 0.0, -1000.0, 0.0, Double.NaN, 0.0)).getAsJsonObject();
		assertEquals(-200.0, fast.getAsJsonArray("v").get(1).getAsDouble());
		assertEquals(0.0, fast.get("yaw").getAsDouble());
	}

	@Test
	void hostFallDamageIsIgnoredAroundAGlideAndWithSlowFalling() {
		long now = 5_000_000_000L;
		assertFalse(HostDamage.ignoredFall("fall", now, Long.MIN_VALUE, false)); // never glided
		assertTrue(HostDamage.ignoredFall("fall", now, now + 1, false)); // gliding or just landed
		assertFalse(HostDamage.ignoredFall("fall", now, now, false)); // 2 s are over
		assertTrue(HostDamage.ignoredFall("fall", now, Long.MIN_VALUE, true));
		assertFalse(HostDamage.ignoredFall("bullet", now, now + 1, true));
	}
}
