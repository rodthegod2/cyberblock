package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.mode.match.MatchLayout.Spot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: Gang Wars spawn points and capture points from the district's landmarks. */
class MatchLayoutTest {
	private static final List<MatchMode.TeamSpec> TWO = MatchMode.BUILT_IN.get("cyberblock:gang_wars").teams().subList(0, 2);
	private static final Match.Zone ZONE = new Match.Zone(0, 0, 100, "Watson");

	@Test
	void withoutLandmarksTeamsSitOppositeOnTheRim() {
		MatchLayout.Layout l = MatchLayout.plan(ZONE, TWO, 3, 6.0, List.of(), 70.0);
		assertEquals(2, l.bases().size());
		assertEquals(80.0, l.bases().get(0).x(), 1e-9);
		assertEquals(-80.0, l.bases().get(1).x(), 1e-9);
		assertEquals(70.0, l.bases().get(0).y(), 1e-9, "no landmark: the caller's height");
		assertEquals(3, l.points().size());
		for (Capture.Point p : l.points()) {
			assertEquals(40.0, Math.hypot(p.x, p.z), 1e-9);
			assertTrue(ZONE.inside(p.x, p.z));
		}
	}

	@Test
	void landmarksBecomeSpawnsAndPoints() {
		List<Spot> spots = List.of(new Spot("East Gate", 75, 30, 10, true), new Spot("West Lot", -70, 22, -5, true), new Spot("Market", 5, 25, 5, true),
			new Spot("Clinic", -20, 26, 30, false), new Spot("Far Away", 900, 10, 900, true));
		MatchLayout.Layout l = MatchLayout.plan(ZONE, TWO, 2, 6.0, spots, 70.0);
		assertEquals("East Gate", l.bases().get(0).name());
		assertEquals("West Lot", l.bases().get(1).name());
		assertEquals(0, l.bases().get(0).team());
		assertEquals(List.of("Market", "Clinic"), l.points().stream().map(p -> p.name).toList(), "the rest, nearest the centre first");
		assertEquals(25.0, l.points().get(0).y, 1e-9);
	}

	@Test
	void aLandmarkFarFromTheIdealSpotIsNotASpawn() {
		List<Spot> spots = List.of(new Spot("Centre", 0, 30, 0, true));
		MatchLayout.Layout l = MatchLayout.plan(ZONE, TWO, 1, 6.0, spots, 70.0);
		assertEquals("Tyger Claws", l.bases().get(0).name(), "the ideal spot, named after the team");
		assertEquals(30.0, l.bases().get(0).y(), 1e-9, "at the nearest landmark's height");
		assertEquals("Centre", l.points().get(0).name, "and the landmark is a capture point");
	}

	@Test
	void killsModesHaveNoPoints() {
		assertTrue(MatchLayout.plan(ZONE, TWO, 0, 6.0, List.of(new Spot("Market", 5, 25, 5, true)), 70.0).points().isEmpty());
	}
}
