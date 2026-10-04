package dev.cyberblock.mode.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Megaplan 8-T1 "Done: tests (feed, decay, tier mapping)". */
class HeatRulesTest {
	private static final List<HeatTier> TIERS = HeatRules.sorted(HeatRules.BUILT_IN);
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

	// --- feed ---

	@Test
	void everyKindOfChaosFeedsTheMeter() {
		for (HeatRules.Source s : HeatRules.Source.values()) {
			if (s != HeatRules.Source.OTHER) {
				assertTrue(s.amount() > 0.0, s + " adds heat");
			}
		}

		// the plan's order of loudness: a PvP kill and a failed breach above a destroyed car, above an explosion, above a hostile kill
		assertTrue(HeatRules.Source.PVP_KILL.amount() > HeatRules.Source.CAR_DESTROYED.amount());
		assertTrue(HeatRules.Source.CAR_DESTROYED.amount() > HeatRules.Source.EXPLOSION.amount());
		assertTrue(HeatRules.Source.EXPLOSION.amount() > HeatRules.Source.HOSTILE_KILL.amount());
		assertEquals(5.0, HeatRules.Source.FAILED_BREACH.amount(), "BreachResults.FAILURE_HEAT");
	}

	@Test
	void feedClampsTo0To100() {
		assertEquals(10.0, HeatRules.feed(5.0, 5.0));
		assertEquals(100.0, HeatRules.feed(98.0, 5.0));
		assertEquals(0.0, HeatRules.feed(2.0, -5.0));
		assertEquals(7.0, HeatRules.feed(7.0, Double.NaN));
	}

	@Test
	void theGainBudgetCapsBursts() {
		HeatMeter m = new HeatMeter();
		double got = 0.0;
		for (int i = 0; i < 20; i++) {
			got += m.feed(5.0, 0); // a TNT chain in one tick
		}

		assertEquals(HeatRules.GAIN_BURST, got, 1e-9, "a burst gives at most the saved budget");
		assertEquals(HeatRules.GAIN_BURST, m.value(), 1e-9);
		assertEquals(HeatRules.GAIN_PER_SECOND, m.feed(100.0, 20), 1e-9, "one second later: one second of budget");
	}

	@Test
	void theMeterNeverPassesItsTop() {
		HeatMeter m = new HeatMeter();
		m.set(99.0, 0);
		assertEquals(1.0, m.feed(10.0, 100), 1e-9);
		assertEquals(100.0, m.value());
	}

	// --- decay ---

	@Test
	void noDecayDuringTheQuietTime() {
		assertEquals(50.0, HeatRules.decay(50.0, 1.0, HeatRules.QUIET_TICKS - 1, TIERS));
		assertTrue(HeatRules.decay(50.0, 1.0, HeatRules.QUIET_TICKS, TIERS) < 50.0);
	}

	@Test
	void decayUsesTheCurrentTiersRate() {
		long quiet = HeatRules.QUIET_TICKS;
		assertEquals(10.0 - HeatRules.CALM_DECAY * 2, HeatRules.decay(10.0, 2.0, quiet, TIERS), 1e-9, "below tier 1");
		assertEquals(45.0 - 0.4 * 2, HeatRules.decay(45.0, 2.0, quiet, TIERS), 1e-9, "tier 2");
		assertEquals(99.0 - 0.25 * 4, HeatRules.decay(99.0, 4.0, quiet, TIERS), 1e-9, "tier 5");
		assertEquals(0.0, HeatRules.decay(0.5, 10.0, quiet, TIERS), "never below 0");
	}

	@Test
	void theMeterCoolsDownToZeroOverTime() {
		HeatMeter m = new HeatMeter();
		m.set(100.0, 0);
		long t = 0;
		while (m.value() > 0.0 && t < 20 * 60 * 30) {
			t += 20;
			m.tick(t, TIERS);
		}

		assertEquals(0.0, m.value());
		assertTrue(t > HeatRules.QUIET_TICKS + 20 * 60, "100 takes minutes to cool, not seconds: " + t / 20 + " s");
	}

	@Test
	void freshChaosRestartsTheQuietTime() {
		HeatMeter m = new HeatMeter();
		m.set(50.0, 0);
		m.tick(400, TIERS);
		double cooled = m.value();
		assertTrue(cooled < 50.0);
		m.feed(1.0, 400);
		m.tick(500, TIERS);
		assertEquals(cooled + 1.0, m.value(), 1e-9, "quiet again for 10 s after the new chaos");
	}

	// --- tiers ---

	@Test
	void tierMapping() {
		assertEquals(0, HeatRules.tier(0.0, TIERS));
		assertEquals(0, HeatRules.tier(19.9, TIERS));
		assertEquals(1, HeatRules.tier(20.0, TIERS));
		assertEquals(2, HeatRules.tier(40.0, TIERS));
		assertEquals(3, HeatRules.tier(79.9, TIERS));
		assertEquals(4, HeatRules.tier(80.0, TIERS));
		assertEquals(5, HeatRules.tier(100.0, TIERS));
	}

	@Test
	void hostsWantedLevelFollowsTheTier() {
		assertEquals(0, HeatRules.wanted(null, true));
		for (HeatTier t : TIERS) {
			assertEquals(t.wanted(), HeatRules.wanted(t, false), "tier " + t.tier());
		}

		HeatTier four = HeatRules.tierOf(85.0, TIERS);
		assertEquals(4, HeatRules.wanted(four, false));
		assertEquals(HeatRules.MAXTAC_WANTED, HeatRules.wanted(four, true), "tier 4: the top chaos host gets Night City's Max-Tac");
		assertEquals(3, HeatRules.wanted(HeatRules.tierOf(65.0, TIERS), true), "tier 3 sends no Max-Tac");
		assertEquals(5, HeatRules.wanted(HeatRules.tierOf(96.0, TIERS), false), "tier 5: full response on everyone");
	}

	// --- squads ---

	@Test
	void tier1PatrolsAroundTheLoudest() {
		List<HeatRules.Order> p = HeatRules.plan(HeatRules.tierOf(25, TIERS), List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 3)), A);
		assertEquals(List.of(new HeatRules.Order(A, HeatRules.Unit.PATROL, 2)), p);
	}

	@Test
	void tier2ChasesTheTwoLoudest() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 3), new HeatRules.Ranked(C, 1));
		List<HeatRules.Order> p = HeatRules.plan(HeatRules.tierOf(45, TIERS), r, A);
		assertEquals(List.of(new HeatRules.Order(A, HeatRules.Unit.OFFICER, 2), new HeatRules.Order(B, HeatRules.Unit.OFFICER, 2)), p);
	}

	@Test
	void tier3AddsHeavyUnitsAndDrones() {
		List<HeatRules.Order> p = HeatRules.plan(HeatRules.tierOf(65, TIERS), List.of(new HeatRules.Ranked(A, 9)), A);
		assertEquals(List.of(new HeatRules.Order(A, HeatRules.Unit.OFFICER, 2), new HeatRules.Order(A, HeatRules.Unit.HEAVY, 1),
			new HeatRules.Order(A, HeatRules.Unit.DRONE, 1)), p);
	}

	@Test
	void tier4MaxTacHuntsTheTopChaosPlayerOnlyWhenSquadsMayHuntIt() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 3));
		assertTrue(HeatRules.plan(HeatRules.tierOf(85, TIERS), r, A).contains(new HeatRules.Order(A, HeatRules.Unit.MAXTAC, 3)));
		// the top player is a host whose Night City answers (not in the hunted list): no Minecraft Max-Tac
		List<HeatRules.Order> host = HeatRules.plan(HeatRules.tierOf(85, TIERS), r, C);
		assertFalse(host.stream().anyMatch(o -> o.unit() == HeatRules.Unit.MAXTAC));
	}

	@Test
	void tier5ChasesEveryoneEvenTheQuiet() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 0), new HeatRules.Ranked(C, 0));
		List<HeatRules.Order> p = HeatRules.plan(HeatRules.tierOf(99, TIERS), r, A);
		for (UUID u : List.of(A, B, C)) {
			assertTrue(p.contains(new HeatRules.Order(u, HeatRules.Unit.OFFICER, 2)), "chased: " + u);
		}

		assertTrue(p.contains(new HeatRules.Order(A, HeatRules.Unit.MAXTAC, 3)));
	}

	@Test
	void quietPlayersAreNotChasedBelowTier5AndNoTierMeansNoSquads() {
		assertTrue(HeatRules.plan(HeatRules.tierOf(45, TIERS), List.of(new HeatRules.Ranked(A, 0)), null).isEmpty());
		assertTrue(HeatRules.plan(null, List.of(new HeatRules.Ranked(A, 9)), A).isEmpty());
	}

	// --- chaos board ---

	@Test
	void chaosHalvesAndRanksTheLoudestFirst() {
		ChaosBoard b = new ChaosBoard();
		b.add(A, 4);
		b.add(B, 10);
		b.add(A, 2);
		assertEquals(B, b.top());
		assertEquals(List.of(B, A), b.ranked(u -> true).stream().map(HeatRules.Ranked::uuid).toList());
		assertEquals(List.of(A), b.ranked(u -> u.equals(A)).stream().map(HeatRules.Ranked::uuid).toList());
		b.decay(ChaosBoard.HALF_LIFE_SECONDS);
		assertEquals(3.0, b.of(A), 1e-9);
		b.decay(ChaosBoard.HALF_LIFE_SECONDS * 20);
		assertNull(b.top(), "forgotten after a long quiet");
	}

	// --- the real wanted level check (Phase 0 e at runtime) ---

	@Test
	void wantedCheckConfirmsWhenTheHostsWantedLevelRises() {
		WantedCheck c = new WantedCheck();
		assertEquals(WantedCheck.State.UNTESTED, c.state());
		assertFalse(c.squadsHunt(), "no squads on a host before the check fails");
		c.sent(0, 0);
		assertEquals(WantedCheck.State.UNTESTED, c.state(), "a 0 starts nothing");
		c.sent(2, 100);
		assertEquals(WantedCheck.State.PENDING, c.state());
		assertFalse(c.update(0, 120));
		assertTrue(c.update(2, 140));
		assertEquals(WantedCheck.State.CONFIRMED, c.state());
		assertFalse(c.squadsHunt());
	}

	@Test
	void wantedCheckFallsBackWhenNothingHappensInTime() {
		WantedCheck c = new WantedCheck();
		c.sent(1, 100);
		assertFalse(c.update(0, 100 + WantedCheck.WINDOW_TICKS - 1));
		assertTrue(c.update(0, 100 + WantedCheck.WINDOW_TICKS));
		assertEquals(WantedCheck.State.FALLBACK, c.state());
		assertTrue(c.squadsHunt());
		c.sent(5, 1000);
		assertFalse(c.update(5, 1001), "decided for the session");
		assertEquals(WantedCheck.State.FALLBACK, c.state());
	}

	// --- heat_tier content ---

	private static HeatTier parse(final String json) {
		return HeatTiers.parse(Identifier.fromNamespaceAndPath("test", "t"), JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void heatTierFilesParse() {
		HeatTier t = parse("{\"schema\":1,\"tier\":4,\"threshold\":75,\"decay\":0.3,\"wanted\":4,\"name\":\"ignored\","
			+ "\"response\":{\"chase\":1,\"officers\":3,\"maxtac\":2}}");
		assertEquals(new HeatTier(4, 75, 0.3, 4, new HeatTier.Response(0, 1, 3, 0, 0, 2, false)), t);
		assertEquals(HeatTier.Response.NONE, parse("{\"schema\":1,\"tier\":1,\"threshold\":10,\"decay\":1,\"wanted\":1}").response());
	}

	@Test
	void badHeatTierFilesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":6,\"threshold\":10,\"decay\":1,\"wanted\":1}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":1,\"threshold\":0,\"decay\":1,\"wanted\":1}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":1,\"threshold\":10,\"decay\":1,\"wanted\":1.5}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":1,\"threshold\":10,\"decay\":1,\"wanted\":1,\"response\":{\"heavy\":9}}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":1,\"threshold\":10,\"decay\":1,\"wanted\":1,\"response\":{\"everyone\":1}}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"tier\":1,\"decay\":1,\"wanted\":1}"));
	}

	@Test
	void contentReplacesTheBuiltInsOnlyWhenConsistent() {
		assertSame(HeatRules.BUILT_IN, HeatTiers.choose(List.of()));
		HeatTier one = new HeatTier(1, 30, 1, 1, HeatTier.Response.NONE), two = new HeatTier(2, 70, 1, 3, HeatTier.Response.NONE);
		assertEquals(List.of(one, two), HeatTiers.choose(List.of(two, one)));
		assertSame(HeatRules.BUILT_IN, HeatTiers.choose(List.of(new HeatTier(1, 70, 1, 1, HeatTier.Response.NONE), new HeatTier(2, 30, 1, 1,
			HeatTier.Response.NONE))), "tier numbers must rise with the thresholds");
		assertEquals(List.of(one), HeatTiers.choose(List.of(one, new HeatTier(1, 50, 1, 1, HeatTier.Response.NONE))), "the first id wins");
	}

	// --- wire ---

	@Test
	void heatWireRoundTrips() {
		assertEquals("{\"t\":\"heat\",\"tier\":5}", HeatWire.down(9));
		assertEquals("{\"t\":\"heat\",\"tier\":0}", HeatWire.down(-1));
		HeatWire.Meter m = new HeatWire.Meter(73, 3, "CbGuest1", true);
		assertEquals(m, HeatWire.parse(HeatWire.ui(m)));
		assertNull(HeatWire.parse("{\"v\":101,\"tier\":3}"));
		assertNull(HeatWire.parse("{\"v\":10,\"tier\":6}"));
		assertNull(HeatWire.parse("not json"));
	}
}
