package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 8-O1 fairness: opt-in only, minimum distance, the simultaneous cap, the match area, the 3 s warning. */
class FairnessTest {
	private static final Fairness.Area AREA = new Fairness.Area(-200, -200, 200, 200);

	private static Fairness.Ask spawn(final double x, final double z, final int units, final int live, final Intensity i, final List<Fairness.Who> who) {
		return new Fairness.Ask(x, z, units, live, i, who, AREA, 0, true);
	}

	private static final Fairness.Who ALICE = new Fairness.Who("Alice", 0, 0, true);

	@Test
	void theWarningIsThreeSeconds() {
		assertEquals(60, Fairness.WARNING_TICKS);
	}

	@Test
	void aSpawnBesideAnOptedInPlayerOutsideTheMinimumDistanceIsFair() {
		assertNull(Fairness.check(spawn(20, 0, 3, 0, Intensity.NORMAL, List.of(ALICE))));
	}

	@Test
	void theMinimumDistanceFollowsTheIntensity() {
		// 10 blocks: inside Normal's 12 and Chill's 16, outside Brutal's 8
		assertEquals("too_close", Fairness.check(spawn(10, 0, 1, 0, Intensity.NORMAL, List.of(ALICE))).key());
		assertEquals("too_close", Fairness.check(spawn(10, 0, 1, 0, Intensity.CHILL, List.of(ALICE))).key());
		assertNull(Fairness.check(spawn(10, 0, 1, 0, Intensity.BRUTAL, List.of(ALICE))));
		assertTrue(Intensity.CHILL.minDistance() > Intensity.NORMAL.minDistance() && Intensity.NORMAL.minDistance() > Intensity.BRUTAL.minDistance());
	}

	@Test
	void theMinimumDistanceCountsEveryPlayerNotOnlyTheOptedIn() {
		Fairness.Who bob = new Fairness.Who("Bob", 30, 0, true);
		Fairness.Refusal r = Fairness.check(spawn(25, 0, 1, 0, Intensity.NORMAL, List.of(ALICE, bob)));
		assertEquals("too_close", r.key());
		assertTrue(r.text().contains("Bob"), r.text());
	}

	@Test
	void aPlayerWhoDidNotOptInKeepsTheOverlordAway() {
		Fairness.Who guest = new Fairness.Who("Guest", 40, 0, false);
		Fairness.Refusal r = Fairness.check(spawn(20, 0, 1, 0, Intensity.NORMAL, List.of(ALICE, guest)));
		assertEquals("not_opted", r.key());
		assertTrue(r.text().contains("Guest"));
		// far enough from the guest (beyond NEAR) and still near Alice: fair
		Fairness.Who far = new Fairness.Who("Guest", 80, 0, false);
		assertNull(Fairness.check(spawn(-20, 0, 1, 0, Intensity.NORMAL, List.of(ALICE, far))));
	}

	@Test
	void nobodyOptedInMeansNoSpawn() {
		Fairness.Who out = new Fairness.Who("Alice", 0, 0, false);
		assertEquals("not_opted", Fairness.check(spawn(20, 0, 1, 0, Intensity.NORMAL, List.of(out))).key());
		assertEquals("no_target", Fairness.check(spawn(100, 0, 1, 0, Intensity.NORMAL, List.of(ALICE))).key(), "nobody opted in within 64");
		assertEquals("no_target", Fairness.check(spawn(20, 0, 1, 0, Intensity.NORMAL, List.of())).key());
	}

	@Test
	void theSimultaneousCapFollowsTheIntensity() {
		assertNull(Fairness.check(spawn(20, 0, 3, 9, Intensity.NORMAL, List.of(ALICE))), "9 + 3 = 12, Normal's cap");
		Fairness.Refusal r = Fairness.check(spawn(20, 0, 4, 9, Intensity.NORMAL, List.of(ALICE)));
		assertEquals("cap", r.key());
		assertTrue(r.text().contains("12"));
		assertEquals("cap", Fairness.check(spawn(20, 0, 1, 6, Intensity.CHILL, List.of(ALICE))).key());
		assertNull(Fairness.check(spawn(20, 0, 8, 16, Intensity.BRUTAL, List.of(ALICE))));
	}

	@Test
	void onlyInsideTheMatchArea() {
		Fairness.Ask outside = new Fairness.Ask(20, 0, 1, 0, Intensity.NORMAL, List.of(ALICE), new Fairness.Area(100, 100, 200, 200), 0, true);
		assertEquals("area", Fairness.check(outside).key());
		Fairness.Ask none = new Fairness.Ask(20, 0, 1, 0, Intensity.NORMAL, List.of(ALICE), null, 0, true);
		assertEquals("area", Fairness.check(none).key(), "no area: no match players to build it from");
	}

	@Test
	void theAreaAroundThePlayers() {
		Fairness.Area a = Fairness.Area.around(List.of(ALICE, new Fairness.Who("Bob", 100, -50, false)), 48);
		assertEquals(new Fairness.Area(-48, -98, 148, 48), a);
		assertTrue(a.contains(0, 0));
		assertFalse(a.contains(149, 0));
		assertNull(Fairness.Area.around(List.of(), 48));
	}

	@Test
	void anEventMayLandOnOptedInPlayersButItsRadiusMustHoldOnlyThem() {
		Fairness.Ask onAlice = new Fairness.Ask(0, 0, 0, 0, Intensity.NORMAL, List.of(ALICE), AREA, 24, false);
		assertNull(Fairness.check(onAlice), "an event has no minimum distance");
		Fairness.Who guest = new Fairness.Who("Guest", 40, 0, false);
		Fairness.Ask wide = new Fairness.Ask(0, 0, 0, 0, Intensity.NORMAL, List.of(ALICE, guest), AREA, 48, false);
		assertEquals("not_opted", Fairness.check(wide).key(), "the guest is inside the event's 48 block radius");
		assertEquals("cap", Fairness.check(new Fairness.Ask(30, 0, 13, 0, Intensity.NORMAL, List.of(ALICE), AREA, 24, false)).key(),
			"an outbreak's squads count toward the cap");
	}

	@Test
	void badPointsAndEmptySpawnsAreRefused() {
		assertEquals("point", Fairness.check(spawn(Double.NaN, 0, 1, 0, Intensity.NORMAL, List.of(ALICE))).key());
		assertEquals("units", Fairness.check(spawn(20, 0, 0, 0, Intensity.NORMAL, List.of(ALICE))).key());
	}
}
