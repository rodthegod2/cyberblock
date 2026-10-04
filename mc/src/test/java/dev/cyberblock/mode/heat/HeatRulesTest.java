package dev.cyberblock.mode.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Megaplan 8-T1 "Done: tests (feed, decay, tier mapping)". */
class HeatRulesTest {
	private static final List<HeatTier> TIERS = HeatRules.sorted(HeatRules.BUILT_IN);
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
	/** 8A's default tier 4 (data/cyberblock/cyberblock/heat_tier/tier_4.json), as the published schema writes it. */
	private static final String TIER_4 = """
		{"schema":1, "tier":4, "name":"Max-Tac Hunt", "description":"The elite squad is sent after the one who caused the most chaos.",
		 "enter":75, "exit":70, "decay_per_s":0.25, "grace_s":25, "host_wanted":4, "top_chaos_wanted":5,
		 "response":{"target":"top_chaos", "max_alive":8, "waves":[{"entry":"cyberblock:maxtac_squad", "every_s":120},
		  {"entry":"cyberblock:ncpd_enforcers", "every_s":60}]}}""";

	private static HeatTier tier(final int n) {
		return HeatRules.byNumber(n, TIERS);
	}

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
	void noDecayDuringTheTiersGraceTime() {
		HeatTier two = tier(2);
		long grace = two.graceSeconds() * 20L;
		assertEquals(40.0, HeatRules.decay(40.0, 1.0, grace - 1, two));
		assertTrue(HeatRules.decay(40.0, 1.0, grace, two) < 40.0);
		assertEquals(10.0, HeatRules.decay(10.0, 1.0, HeatRules.CALM_GRACE_TICKS - 1, null), "below tier 1: the calm grace time");
	}

	@Test
	void decayUsesTheCurrentTiersRate() {
		long quiet = 20L * 60;
		assertEquals(10.0 - HeatRules.CALM_DECAY * 2, HeatRules.decay(10.0, 2.0, quiet, null), 1e-9, "below tier 1");
		assertEquals(45.0 - 0.35 * 2, HeatRules.decay(45.0, 2.0, quiet, tier(2)), 1e-9, "tier 2");
		assertEquals(99.0 - 0.2 * 4, HeatRules.decay(99.0, 4.0, quiet, tier(5)), 1e-9, "tier 5");
		assertEquals(0.0, HeatRules.decay(0.5, 10.0, quiet, null), "never below 0");
	}

	@Test
	void theMeterCoolsDownToZeroOverTime() {
		HeatMeter m = new HeatMeter();
		m.set(100.0, 0);
		long t = 0;
		int current = HeatRules.next(m.value(), 0, TIERS);
		while (m.value() > 0.0 && t < 20 * 60 * 60) {
			t += 20;
			m.tick(t, HeatRules.byNumber(current, TIERS));
			current = HeatRules.next(m.value(), current, TIERS);
		}

		assertEquals(0.0, m.value());
		assertEquals(0, current);
		assertTrue(t > 20 * 60 * 3, "100 takes minutes to cool, not seconds: " + t / 20 + " s");
	}

	@Test
	void freshChaosRestartsTheGraceTime() {
		HeatMeter m = new HeatMeter();
		m.set(50.0, 0);
		HeatTier three = tier(3);
		m.tick(three.graceSeconds() * 20L + 200, three);
		double cooled = m.value();
		assertTrue(cooled < 50.0);
		m.feed(1.0, 1000);
		m.tick(1000 + three.graceSeconds() * 20L - 20, three);
		assertEquals(cooled + 1.0, m.value(), 1e-9, "quiet again for the tier's grace time after the new chaos");
	}

	// --- tiers ---

	@Test
	void tiersAreEnteredAtTheirEnterValue() {
		assertEquals(0, HeatRules.next(0.0, 0, TIERS));
		assertEquals(0, HeatRules.next(14.9, 0, TIERS));
		assertEquals(1, HeatRules.next(15.0, 0, TIERS));
		assertEquals(2, HeatRules.next(35.0, 0, TIERS));
		assertEquals(3, HeatRules.next(74.9, 0, TIERS));
		assertEquals(4, HeatRules.next(75.0, 0, TIERS));
		assertEquals(4, HeatRules.next(85.0, 1, TIERS), "a jump goes straight to the highest tier reached");
		assertEquals(5, HeatRules.next(100.0, 0, TIERS));
	}

	@Test
	void tiersAreLeftOnlyBelowTheirExitValue() {
		assertEquals(4, HeatRules.next(72.0, 4, TIERS), "between exit 70 and enter 75: still tier 4");
		assertEquals(3, HeatRules.next(69.9, 4, TIERS));
		assertEquals(3, HeatRules.next(72.0, 3, TIERS), "and rising back to 72 does not re-enter tier 4");
		assertEquals(2, HeatRules.next(40.0, 4, TIERS), "a big drop walks down past every exit it fell below");
		assertEquals(1, HeatRules.next(12.0, 1, TIERS));
		assertEquals(0, HeatRules.next(9.9, 1, TIERS));
		assertEquals(3, HeatRules.next(60.0, 7, TIERS), "a tier that is no longer loaded starts over from the value");
	}

	@Test
	void hostsWantedLevelFollowsTheTier() {
		assertEquals(0, HeatRules.wanted(null, true));
		for (HeatTier t : TIERS) {
			assertEquals(t.hostWanted(), HeatRules.wanted(t, false), "tier " + t.tier());
		}

		assertEquals(4, HeatRules.wanted(tier(4), false));
		assertEquals(5, HeatRules.wanted(tier(4), true), "tier 4: the top chaos host gets Night City's Max-Tac (top_chaos_wanted)");
		assertEquals(3, HeatRules.wanted(tier(3), true), "tier 3 has no top_chaos_wanted");
		assertEquals(5, HeatRules.wanted(tier(5), false), "tier 5: full response on everyone");
	}

	@Test
	void aLinkedHostWithCyberpunkOffIsAnsweredByMinecraft() {
		assertTrue(HeatRules.minecraftOnly(false, false), "a guest");
		assertTrue(HeatRules.minecraftOnly(true, false), "linked, Cyberpunk Off: no real wanted level, so squads");
		assertFalse(HeatRules.minecraftOnly(true, true), "an active host: Night City answers (unless its check fell back)");
	}

	// --- squads ---

	@Test
	void paletteEntriesBecomeMinecraftSquads() {
		assertEquals(new HeatRules.Squad(HeatRules.Unit.OFFICER, 2), HeatRules.squad("cyberblock:ncpd_patrol"));
		assertEquals(new HeatRules.Squad(HeatRules.Unit.HEAVY, 2), HeatRules.squad("cyberblock:ncpd_enforcers"));
		assertEquals(new HeatRules.Squad(HeatRules.Unit.DRONE, 2), HeatRules.squad("cyberblock:wyvern_drones"));
		assertEquals(new HeatRules.Squad(HeatRules.Unit.MAXTAC, 2), HeatRules.squad("cyberblock:maxtac_squad"));
		assertEquals(HeatRules.Unit.DRONE, HeatRules.squad("mypack:police_drone_pair").unit(), "a datapack's entry by its words");
		assertEquals(HeatRules.Unit.OFFICER, HeatRules.squad("mypack:gangers").unit());
	}

	@Test
	void tier1PatrolsAroundEveryone() {
		List<HeatRules.Order> p = HeatRules.plan(tier(1), List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 0)), A);
		assertEquals(List.of(new HeatRules.Order(A, 0, HeatRules.Unit.OFFICER, 2, 90 * 20, false),
			new HeatRules.Order(B, 0, HeatRules.Unit.OFFICER, 2, 90 * 20, false)), p);
	}

	@Test
	void tier2ChasesTheTwoLoudest() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 3), new HeatRules.Ranked(C, 1));
		List<HeatRules.Order> p = HeatRules.plan(tier(2), r, A);
		assertEquals(List.of(new HeatRules.Order(A, 0, HeatRules.Unit.OFFICER, 2, 45 * 20, true),
			new HeatRules.Order(B, 0, HeatRules.Unit.OFFICER, 2, 45 * 20, true)), p);
	}

	@Test
	void tier3SendsEnforcersAndDrones() {
		List<HeatRules.Order> p = HeatRules.plan(tier(3), List.of(new HeatRules.Ranked(A, 9)), A);
		assertEquals(List.of(new HeatRules.Order(A, 0, HeatRules.Unit.HEAVY, 2, 60 * 20, true),
			new HeatRules.Order(A, 1, HeatRules.Unit.DRONE, 2, 90 * 20, true)), p);
	}

	@Test
	void tier4MaxTacHuntsTheTopChaosPlayerOnlyWhenSquadsMayHuntIt() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 3));
		List<HeatRules.Order> p = HeatRules.plan(tier(4), r, A);
		assertTrue(p.contains(new HeatRules.Order(A, 0, HeatRules.Unit.MAXTAC, 2, 120 * 20, true)));
		assertTrue(p.stream().allMatch(o -> o.target().equals(A)), "only the top chaos player");
		// the top player is a host whose Night City answers (not in the hunted list): no Minecraft squads at all
		assertTrue(HeatRules.plan(tier(4), r, C).isEmpty());
	}

	@Test
	void tier5ChasesEveryoneEvenTheQuiet() {
		List<HeatRules.Ranked> r = List.of(new HeatRules.Ranked(A, 9), new HeatRules.Ranked(B, 0), new HeatRules.Ranked(C, 0));
		List<HeatRules.Order> p = HeatRules.plan(tier(5), r, A);
		for (UUID u : List.of(A, B, C)) {
			assertTrue(p.contains(new HeatRules.Order(u, 0, HeatRules.Unit.MAXTAC, 2, 90 * 20, true)), "chased: " + u);
		}

		assertEquals(9, p.size(), "three waves each");
	}

	@Test
	void quietPlayersAreNotChasedByLoudestTiersAndNoTierMeansNoSquads() {
		assertTrue(HeatRules.plan(tier(2), List.of(new HeatRules.Ranked(A, 0)), null).isEmpty());
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
		assertFalse(c.squadsHunt(), "no squads on a host before a check fails");
		assertFalse(c.sent(0, 0, 0), "a 0 starts nothing");
		assertTrue(c.sent(2, 100, 0));
		assertEquals(WantedCheck.State.PENDING, c.state());
		assertEquals(1, c.needed(), "one star more than before is enough (Night City may cap it)");
		assertFalse(c.update(0, 120));
		assertTrue(c.update(1, 140));
		assertEquals(WantedCheck.State.CONFIRMED, c.state());
		assertFalse(c.squadsHunt());
		assertFalse(c.sent(5, 200, 1), "confirmed for the session");
	}

	@Test
	void starsVAlreadyHadDoNotConfirm() {
		WantedCheck c = new WantedCheck();
		assertFalse(c.sent(2, 100, 3), "V already has 3 stars: a 2 tests nothing");
		assertEquals(WantedCheck.State.UNTESTED, c.state());
		assertTrue(c.sent(4, 200, 3));
		assertEquals(4, c.needed());
		assertFalse(c.update(3, 300), "still the old 3 stars");
		assertTrue(c.update(3, 200 + WantedCheck.WINDOW_TICKS));
		assertEquals(WantedCheck.State.FALLBACK, c.state());
		assertTrue(c.squadsHunt());
	}

	@Test
	void aRaiseHeldBackForA30sRetryStillConfirms() {
		WantedCheck c = new WantedCheck();
		assertTrue(c.sent(2, 100, 0));
		assertTrue(WantedCheck.WINDOW_TICKS >= 45 * 20, "longer than 8S's 30 s retry of a held raise");
		assertFalse(c.update(0, 100 + 30 * 20), "in a scene: held");
		assertTrue(c.update(2, 100 + 32 * 20), "applied by the retry");
		assertEquals(WantedCheck.State.CONFIRMED, c.state());
	}

	@Test
	void aFailedCheckIsTestedAgainAtTheNextRaise() {
		WantedCheck c = new WantedCheck();
		c.sent(1, 100, 0);
		assertFalse(c.update(0, 100 + WantedCheck.WINDOW_TICKS - 1));
		assertTrue(c.update(0, 100 + WantedCheck.WINDOW_TICKS));
		assertEquals(WantedCheck.State.FALLBACK, c.state());
		assertTrue(c.squadsHunt());
		assertTrue(c.sent(3, 5000, 0), "the next raise tests again");
		assertTrue(c.squadsHunt(), "the fallback stays on while the re-test runs");
		assertTrue(c.update(1, 5100));
		assertEquals(WantedCheck.State.CONFIRMED, c.state());
		assertFalse(c.squadsHunt());
	}

	// --- heat_tier content ---

	private static HeatTier parse(final String json) {
		return HeatTiers.parse(Identifier.fromNamespaceAndPath("test", "t"), JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void eightAsTierFileParses() {
		HeatTier t = parse(TIER_4);
		assertEquals(new HeatTier(4, "Max-Tac Hunt", 75, 70, 0.25, 25, 4, 5, new HeatTier.Response(HeatTier.Target.TOP_CHAOS, 8,
			List.of(new HeatTier.Wave("cyberblock:maxtac_squad", 120), new HeatTier.Wave("cyberblock:ncpd_enforcers", 60)))), t);
		assertEquals(tier(4), t, "the built-in tier 4 is 8A's default");
		assertEquals(-1, parse(TIER_4.replace("\"top_chaos_wanted\":5,", "")).topChaosWanted(), "top_chaos_wanted is optional");
	}

	@Test
	void badHeatTierFilesAreRefused() {
		for (String[] bad : new String[][] {{"\"tier\":4", "\"tier\":6"}, {"\"enter\":75", "\"enter\":0"}, {"\"exit\":70", "\"exit\":75"},
			{"\"host_wanted\":4", "\"host_wanted\":1.5"}, {"\"max_alive\":8", "\"max_alive\":25"}, {"\"top_chaos\"", "\"nobody\""},
			{"\"every_s\":120", "\"every_s\":2"}, {"\"cyberblock:maxtac_squad\"", "\"Max Tac\""}, {"\"grace_s\":25", "\"grace_s\":25, \"threshold\":75"},
			{"\"name\":\"Max-Tac Hunt\", ", ""}, {"\"decay_per_s\":0.25", "\"decay_per_s\":6"}}) {
			assertThrows(IllegalArgumentException.class, () -> parse(TIER_4.replace(bad[0], bad[1])), bad[1]);
		}

		assertThrows(IllegalArgumentException.class, () -> parse(TIER_4.replaceAll("(?s)\"waves\":\\[.*]}", "\"waves\":[]}")), "no waves");
	}

	@Test
	void everyShippedTierFileParsesAndReplacesTheBuiltIns() throws IOException {
		Path dir = Path.of("src/main/resources/data/cyberblock/cyberblock/heat_tier");
		Assumptions.assumeTrue(Files.isDirectory(dir), "8A's heat_tier defaults are not in this tree yet (they merge before 8T)");
		List<HeatTier> loaded = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				loaded.add(parse(Files.readString(f, StandardCharsets.UTF_8))); // a skipped file would throw here
			}
		}

		assertEquals(5, loaded.size());
		List<HeatTier> active = HeatTiers.choose(loaded);
		assertNotSame(HeatRules.BUILT_IN, active, "the content is used, not the built-ins");
		assertEquals(HeatRules.BUILT_IN, active, "and the built-ins stay the same numbers as the shipped files");
	}

	@Test
	void contentReplacesTheBuiltInsOnlyWhenConsistent() {
		assertSame(HeatRules.BUILT_IN, HeatTiers.choose(List.of()));
		HeatTier one = tier(1), two = tier(2);
		assertEquals(List.of(one, two), HeatTiers.choose(List.of(two, one)));
		HeatTier lowTwo = new HeatTier(2, "x", 10, 5, 0.1, 0, 1, -1, two.response());
		assertSame(HeatRules.BUILT_IN, HeatTiers.choose(List.of(one, lowTwo)), "enter must rise with the tier number");
		assertEquals(List.of(one), HeatTiers.choose(List.of(one, new HeatTier(1, "y", 50, 40, 1, 0, 1, -1, one.response()))), "the first id wins");
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
