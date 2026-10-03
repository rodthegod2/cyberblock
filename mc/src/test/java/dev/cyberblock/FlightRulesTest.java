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

	/** Plenty of air below and room for the gliding box. */
	private static boolean start(final boolean jump, final boolean elytra, final FlightRules.Fall fall) {
		return FlightRules.start(jump, elytra, fall.speed(), fall.fastNanos(), 5.0, false);
	}

	@Test
	void jumpWhileFallingStartsAGlide() {
		FlightRules.Fall fall = falling(2.0, 4);
		assertEquals(2.0, fall.speed(), 1e-9);
		assertTrue(start(true, true, fall));
	}

	@Test
	void notWithoutElytraNorWithoutJumpNorStandingStill() {
		FlightRules.Fall fall = falling(2.0, 4);
		assertFalse(start(true, false, fall));
		assertFalse(start(false, true, fall));
		assertFalse(start(true, true, falling(0.9, 4)));
		assertFalse(start(true, true, falling(-5.0, 4)), "a jump going up");
	}

	/** In-game 2026-10-03: glides opened 0.4 m above the ground and touched down within the second. */
	@Test
	void needsThreeBlocksOfAirBelow() {
		FlightRules.Fall fall = falling(6.0, 4);
		assertFalse(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), 0.4, false));
		assertFalse(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), 2.99, false));
		assertTrue(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), 3.0, false));
		assertTrue(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), 5.0, false));
		assertFalse(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), Double.NaN, false));
		FlightRules.Fall fast = falling(20.0, 20);
		assertFalse(FlightRules.start(false, true, fast.speed(), fast.fastNanos(), 2.0, false), "nor by itself");
	}

	/** V's feet inside the rounded-up barrier of Night City's ground: the glide would start stuck in it. */
	@Test
	void neverWhenTheGlidingBoxOverlapsABlock() {
		FlightRules.Fall fall = falling(6.0, 4);
		assertFalse(FlightRules.start(true, true, fall.speed(), fall.fastNanos(), 5.0, true));
		FlightRules.Fall fast = falling(20.0, 20);
		assertFalse(FlightRules.start(false, true, fast.speed(), fast.fastNanos(), 5.0, true));
	}

	@Test
	void theJumpUsesTheNewestTwoSamples() {
		FlightRules.Fall fall = new FlightRules.Fall();
		assertEquals(0.0, fall.speed());
		fall.sample(1_000_000_000L, 0.0, 100.0, 0.0);
		assertEquals(0.0, fall.speed(), "one sample is no speed");
		fall.sample(1_000_000_000L + TICK, 0.0, 99.9, 0.0);
		assertEquals(2.0, fall.speed(), 1e-9);
		assertTrue(start(true, true, fall), "two samples are enough");
		// the top of a jump: still rising over the last three ticks, already falling in the newest one
		FlightRules.Fall top = new FlightRules.Fall();
		double[] y = {100.0, 100.5, 100.7, 100.6};
		for (int i = 0; i < y.length; i++) {
			top.sample(i * TICK, 0.0, y[i], 0.0);
		}

		assertEquals(2.0, top.speed(), 1e-9);
		assertTrue(start(true, true, top));
	}

	@Test
	void aLongFastFallStartsByItself() {
		FlightRules.Fall short_ = falling(13.0, 6); // 5 intervals: 0.25 s
		assertFalse(start(false, true, short_));
		FlightRules.Fall fall = falling(13.0, 7); // 0.3 s
		assertEquals(300_000_000L, fall.fastNanos());
		assertTrue(start(false, true, fall));
		assertFalse(start(false, false, fall));
		FlightRules.Fall slow = falling(11.9, 40);
		assertEquals(0L, slow.fastNanos(), "12 m/s is the threshold");
		assertFalse(start(false, true, slow));
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
		assertEquals(2.0, fall.speed(), 1e-9);
	}

	@Test
	void withoutDoubleJumpEveryPressInTheAirCounts() {
		FlightRules.AirJumps jumps = new FlightRules.AirJumps();
		assertTrue(jumps.press(true, true, false));
		assertFalse(jumps.press(true, false, false));
		assertTrue(jumps.press(true, true, false));
	}

	@Test
	void withDoubleJumpTheFirstPressInTheAirIsVs() {
		FlightRules.AirJumps jumps = new FlightRules.AirJumps();
		assertTrue(jumps.press(false, true, true), "the jump off the ground is passed on (it cannot open the elytra)");
		assertFalse(jumps.press(true, true, true), "V's double jump");
		assertFalse(jumps.press(true, false, true));
		assertTrue(jumps.press(true, true, true), "the second press in the air opens the elytra");
		assertTrue(jumps.press(true, true, true));
		assertFalse(jumps.press(false, false, true), "landed: the count starts again");
		assertFalse(jumps.press(true, true, true));
		assertTrue(jumps.press(true, true, true));
		jumps.reset();
		assertFalse(jumps.press(true, true, true));
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
	void aGlideTheHostNeverFollowsIsCalledOffAfterHalfASecond() {
		long start = 1_000_000_000L;
		FlightRules.Follow follow = new FlightRules.Follow();
		follow.start(start);
		assertTrue(follow.ok(start + 100_000_000L, start + 90_000_000L, false));
		assertTrue(follow.ok(start + 499_999_999L, start + 490_000_000L, false));
		assertFalse(follow.ok(start + 500_000_000L, start + 490_000_000L, false));
		assertFalse(follow.seen());
	}

	@Test
	void aGlideIsCalledOffWhenTheHostStopsFollowing() {
		long start = 1_000_000_000L;
		FlightRules.Follow follow = new FlightRules.Follow();
		follow.start(start);
		assertTrue(follow.ok(start + 150_000_000L, start + 140_000_000L, true));
		assertTrue(follow.seen());
		assertTrue(follow.ok(start + 5_000_000_000L, start + 4_990_000_000L, true), "no time limit once it follows");
		assertFalse(follow.ok(start + 5_050_000_000L, start + 5_040_000_000L, false));
	}

	@Test
	void onlyPosesAfterTheStartCount() {
		long start = 1_000_000_000L;
		FlightRules.Follow follow = new FlightRules.Follow();
		follow.start(start);
		// the previous glide's last pose still says fly: it does not count for this one
		assertTrue(follow.ok(start + 10_000_000L, start - 5_000_000L, true));
		assertFalse(follow.seen());
		follow.start(start);
		assertTrue(follow.ok(start + 20_000_000L, start + 15_000_000L, true));
		follow.start(start + 1_000_000_000L);
		assertFalse(follow.seen(), "a new glide starts unfollowed");
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
