package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 8-O1: the rule-based Auto-Director spends by match pace and Heat. */
class AutoDirectorTest {
	private static final List<AutoDirector.Spawn> PALETTE = List.of(
		new AutoDirector.Spawn("cyberblock:zombies", "monster", null, 6, 3, true),
		new AutoDirector.Spawn("cyberblock:tyger_claws", "gang", "ganger", 14, 3, true),
		new AutoDirector.Spawn("cyberblock:maelstrom", "gang", "ganger", 24, 4, true),
		new AutoDirector.Spawn("cyberblock:ncpd", "law", "ncpd", 18, 3, true),
		new AutoDirector.Spawn("cyberblock:maxtac", "law", "maxtac", 45, 2, true));
	private static final List<AutoDirector.Event> EVENTS = List.of(
		new AutoDirector.Event("cyberblock:airstrike", "airstrike", 30, true),
		new AutoDirector.Event("cyberblock:supply_drop", "supply_drop", 10, true),
		new AutoDirector.Event("cyberblock:weather", "weather", 5, true));

	private static AutoDirector.Input in(final double points, final int heat, final double pace, final double since, final double roll) {
		return new AutoDirector.Input(points, Intensity.NORMAL, 0, heat, pace, since, PALETTE, EVENTS, roll);
	}

	@Test
	void itWaitsLongerInAHotMatch() {
		assertEquals(8.0, AutoDirector.gap(0.0));
		assertEquals(30.0, AutoDirector.gap(1.0));
		assertNull(AutoDirector.decide(in(100, 0, 0.0, 7.9, 0.9)), "too soon after its last action");
		assertNotNull(AutoDirector.decide(in(100, 0, 0.0, 8.0, 0.9)));
		assertNull(AutoDirector.decide(in(100, 0, 0.5, 15.0, 0.9)), "a busier match waits 19 s");
	}

	@Test
	void aHotMatchGetsABreatherUnlessThePointsAreFull() {
		assertNull(AutoDirector.decide(in(100, 0, 0.9, 60.0, 0.9)));
		assertNotNull(AutoDirector.decide(in(Intensity.NORMAL.maxPoints(), 0, 0.9, 60.0, 0.9)));
	}

	@Test
	void lowHeatSendsGangsAndMonstersNeverTheLaw() {
		AutoDirector.Choice c = AutoDirector.decide(in(200, 10, 0.0, 60.0, 0.9));
		assertEquals(new AutoDirector.Choice(false, "cyberblock:maelstrom"), c, "a calm match: the dearest affordable non-law entry");
	}

	@Test
	void highHeatSendsTheLawAndMaxTacOnlyFromHeat80() {
		assertEquals("cyberblock:ncpd", AutoDirector.decide(in(200, 50, 0.0, 60.0, 0.9)).id());
		assertEquals("cyberblock:maxtac", AutoDirector.decide(in(200, 85, 0.0, 60.0, 0.9)).id());
	}

	@Test
	void aBusierMatchKeepsPointsBack() {
		// pace 0.6: the budget is 70 % of 30 points = 21, so the 14-point Tyger Claws rather than the affordable 24-point Maelstrom
		assertEquals("cyberblock:tyger_claws", AutoDirector.decide(in(30, 0, 0.6, 60.0, 0.9)).id());
		assertEquals("cyberblock:maelstrom", AutoDirector.decide(in(30, 0, 0.0, 60.0, 0.9)).id(), "a calm match spends it all");
		// pace 0.7: a budget of 65 % of 8 = 5.2 holds nothing: the cheapest affordable
		assertEquals("cyberblock:zombies", AutoDirector.decide(in(8, 0, 0.7, 60.0, 0.9)).id());
	}

	@Test
	void nothingAffordableOrOverTheCapMeansWait() {
		assertNull(AutoDirector.decide(in(5, 0, 0.0, 60.0, 0.9)));
		AutoDirector.Input full = new AutoDirector.Input(200, Intensity.NORMAL, 10, 0, 0.0, 60.0, PALETTE, EVENTS, 0.9);
		assertNull(AutoDirector.decide(full), "every entry would pass the cap of 12");
		AutoDirector.Input coolingDown = new AutoDirector.Input(200, Intensity.NORMAL, 0, 0, 0.0, 60.0,
			List.of(new AutoDirector.Spawn("a:b", "gang", "ganger", 5, 1, false)), List.of(), 0.9);
		assertNull(AutoDirector.decide(coolingDown));
	}

	@Test
	void fromHeat40SomeDecisionsAreEventsButNeverWeatherOrSupplies() {
		assertEquals(new AutoDirector.Choice(true, "cyberblock:airstrike"), AutoDirector.decide(in(200, 45, 0.0, 60.0, 0.1)));
		assertFalse(AutoDirector.decide(in(200, 45, 0.0, 60.0, 0.3)).event(), "the roll missed the 25 % chance");
		assertFalse(AutoDirector.decide(in(200, 20, 0.0, 60.0, 0.1)).event(), "Heat below 40");
		AutoDirector.Input onlyNice = new AutoDirector.Input(200, Intensity.NORMAL, 0, 60, 0.0, 60.0, PALETTE,
			List.of(new AutoDirector.Event("x:drop", "supply_drop", 10, true)), 0.0);
		assertFalse(AutoDirector.decide(onlyNice).event());
	}

	@Test
	void badPaceIsCalm() {
		assertEquals(0.0, AutoDirector.clamp(Double.NaN));
		assertEquals(1.0, AutoDirector.clamp(4.0));
	}
}
