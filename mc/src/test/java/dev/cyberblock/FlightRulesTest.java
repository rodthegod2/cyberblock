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
	void aMenuPauseGivesAHostThatHasNotFollowedYetItsTimeAgain() {
		long start = 1_000_000_000L;
		FlightRules.Follow follow = new FlightRules.Follow();
		follow.start(start);
		follow.restart(start + 3_000_000_000L); // a Cyberpunk menu was open for 3 s
		assertTrue(follow.ok(start + 3_400_000_000L, start + 3_390_000_000L, false));
		assertFalse(follow.ok(start + 3_500_000_000L, start + 3_490_000_000L, false));
		follow.start(start);
		assertTrue(follow.ok(start + 100_000_000L, start + 90_000_000L, true));
		follow.restart(start + 3_000_000_000L);
		assertTrue(follow.seen(), "a host that follows stays followed");
		assertFalse(follow.ok(start + 3_100_000_000L, start + 3_090_000_000L, false));
	}

	@Test
	void aGlideIsCalledOffWhenTheHostsVStaysFarFromSteveForASecond() {
		long start = 1_000_000_000L;
		FlightRules.Follow follow = new FlightRules.Follow();
		follow.start(start);
		assertFalse(follow.tooFarApart(start + 10_000_000L, 100.0), "nothing is apart before the host follows");
		assertTrue(follow.ok(start + 100_000_000L, start + 90_000_000L, true));
		long t = start + 200_000_000L;
		assertFalse(follow.tooFarApart(t, 10.0), "a fast dive trails V by about 10 blocks");
		assertFalse(follow.tooFarApart(t, 16.5));
		assertFalse(follow.tooFarApart(t + 999_999_999L, 30.0));
		assertTrue(follow.tooFarApart(t + 1_000_000_000L, 30.0), "a second apart: called off");
		// a moment together starts the clock again
		follow.start(start);
		assertTrue(follow.ok(start + 100_000_000L, start + 90_000_000L, true));
		assertFalse(follow.tooFarApart(t, 20.0));
		assertFalse(follow.tooFarApart(t + 600_000_000L, 2.0));
		assertFalse(follow.tooFarApart(t + 700_000_000L, 20.0));
		assertFalse(follow.tooFarApart(t + 1_600_000_000L, 20.0));
		assertTrue(follow.tooFarApart(t + 1_700_000_000L, 20.0));
		assertFalse(follow.tooFarApart(t + 1_800_000_000L, Double.NaN), "not a number: not apart");
		// a menu pause (V cannot follow while Cyberpunk is paused) starts it again too
		assertFalse(follow.tooFarApart(t + 2_000_000_000L, 20.0));
		follow.restart(t + 2_500_000_000L);
		assertFalse(follow.tooFarApart(t + 3_400_000_000L, 20.0));
		assertTrue(follow.tooFarApart(t + 4_400_000_000L, 20.0));
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

	/** Plan retest2 R5: what a knockback (blocks per tick) has to be to launch Steve off V's ground. */
	@Test
	void anUpwardKickLaunches() {
		assertTrue(FlightRules.launch(0.0, 1.22, 0.0, true), "his own wind charge at his feet");
		assertTrue(FlightRules.launch(0.31, 0.34, 0.0, true), "a wind charge a block and a half beside him");
		assertTrue(FlightRules.launch(0.0, 0.25, -0.35, true), "just enough up, fast enough");
		assertFalse(FlightRules.launch(0.1, 0.1, 0.0, true), "a weak push from far off");
		assertFalse(FlightRules.launch(1.5, 0.1, 0.0, true), "sideways: he stays with V");
		assertFalse(FlightRules.launch(0.0, 0.3, 0.0, true), "up but too slow");
		assertFalse(FlightRules.launch(0.0, -1.5, 0.0, true), "down");
	}

	/** A motion packet is any knockback the server gives him: a hit's 0.4 up is no launch. */
	@Test
	void aMotionKickNeedsMoreLift() {
		assertFalse(FlightRules.launch(0.5, 0.4, 0.0, false), "a mob's hit");
		assertFalse(FlightRules.launch(0.0, 0.59, 0.0, false));
		assertTrue(FlightRules.launch(0.0, 0.6, 0.0, false));
		assertTrue(FlightRules.launch(0.2, 1.1, 0.3, false));
	}

	@Test
	void aNonFiniteKickIsNoLaunch() {
		assertFalse(FlightRules.launch(Double.NaN, 1.0, 0.0, true));
		assertFalse(FlightRules.launch(0.0, Double.POSITIVE_INFINITY, 0.0, true));
		assertFalse(FlightRules.launch(0.0, 1.0, Double.NEGATIVE_INFINITY, false));
	}

	/** On the ground or in water, but not in the first 100 ms (the tick of the launch may still say on ground). */
	@Test
	void aLaunchLands() {
		assertFalse(FlightRules.launchLanded(true, false, 50_000_000L));
		assertTrue(FlightRules.launchLanded(true, false, 100_000_000L));
		assertTrue(FlightRules.launchLanded(false, true, 2_000_000_000L));
		assertFalse(FlightRules.launchLanded(false, false, 2_000_000_000L));
		assertFalse(FlightRules.launchLanded(false, true, 99_999_999L));
	}

	/** In a launch, the jump key opens the elytra like a glide start (falling, with air below). */
	@Test
	void aLaunchTurnsIntoAGlide() {
		assertTrue(FlightRules.launchToGlide(true, true, 1.5, 5.0));
		assertFalse(FlightRules.launchToGlide(false, true, 1.5, 5.0), "no jump");
		assertFalse(FlightRules.launchToGlide(true, false, 1.5, 5.0), "no elytra");
		assertFalse(FlightRules.launchToGlide(true, true, 0.5, 5.0), "still rising or at the top");
		assertFalse(FlightRules.launchToGlide(true, true, 1.5, 2.9), "too close to the ground");
		assertFalse(FlightRules.launchToGlide(true, true, Double.NaN, 5.0));
	}

	/**
	 * After touchdown Steve waits at the landing spot until the host has put V there: half a second at most, less once a pose that
	 * arrived after touchdown says V is no longer following and stands within 1.5 m of the spot.
	 */
	@Test
	void theSettleEndsWhenVIsAtTheLandingSpot() {
		long touchdown = 1_000_000_000L;
		assertFalse(FlightRules.settled(touchdown + 10_000_000L, touchdown, touchdown - 5_000_000L, false, 0.2), "a pose from before touchdown");
		assertFalse(FlightRules.settled(touchdown + 30_000_000L, touchdown, touchdown + 20_000_000L, true, 0.2), "V still follows");
		assertFalse(FlightRules.settled(touchdown + 30_000_000L, touchdown, touchdown + 20_000_000L, false, 1.5), "V is not there");
		assertTrue(FlightRules.settled(touchdown + 30_000_000L, touchdown, touchdown + 20_000_000L, false, 1.49));
		assertTrue(FlightRules.settled(touchdown + 500_000_000L, touchdown, touchdown - 5_000_000L, true, 40.0), "the upper bound");
		assertTrue(FlightRules.settled(touchdown + 30_000_000L, touchdown, touchdown + 20_000_000L, false, 0.0));
		assertFalse(FlightRules.settled(touchdown + 30_000_000L, touchdown, touchdown + 20_000_000L, false, Double.NaN));
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
