package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 0 check (h) at runtime: the desk verdict first, the written 50 m fallback once Cyberpunk refuses a far target for range. */
class RangeRuleTest {
	@Test
	void everyStreamedTargetUntilCyberpunkSaysOtherwise() {
		RangeRule rule = new RangeRule();
		assertFalse(rule.fallback());
		assertTrue(rule.allows(140.0, true));
		assertFalse(rule.allows(10.0, false), "a target the owner does not stream is never sent");
		String first = rule.takeLine();
		assertTrue(first.startsWith("hack range: every streamed target"), first);
		assertNull(rule.takeLine(), "one line per path");
	}

	@Test
	void aFarRangeRefusalSwitchesTheFallbackOn() {
		RangeRule rule = new RangeRule();
		rule.takeLine();
		assertFalse(rule.observe(true, "", 120.0, "veh_lock"), "an answer that worked");
		assertFalse(rule.observe(false, "target gone", 120.0, "veh_lock"), "not streamed is not a range limit");
		assertFalse(rule.observe(false, "out of range", 40.0, "veh_lock"), "a near refusal says nothing about far targets");
		assertTrue(rule.observe(false, "Out of range", 80.0, "short_circuit"));
		assertTrue(rule.fallback());
		assertFalse(rule.observe(false, "no line of sight", 90.0, "ping"), "already on");
		assertTrue(rule.allows(50.0, true));
		assertFalse(rule.allows(50.5, true));
		String line = rule.takeLine();
		assertTrue(line.startsWith("hack range: fallback active: remote hacks only within 50 m of V"), line);
		assertTrue(line.contains("short_circuit at 80 m"), line);
		rule.reset();
		assertFalse(rule.fallback());
		assertTrue(rule.allows(500.0, true));
	}

	@Test
	void sightAndDistanceWordsCount() {
		for (String reason : new String[] {"line of sight blocked", "too far", "distance", "target not visible", "unreachable", "obstructed",
			"not hackable (no line of sight)", "Too far from V"}) {
			RangeRule rule = new RangeRule();
			assertTrue(rule.observe(false, reason, 60.0, "x"), reason);
		}
	}

	@Test
	void theScriptsOwnAnswersAndUnknownPlacesNeverCount() {
		RangeRule rule = new RangeRule();
		for (int i = 0; i < 5; i++) {
			assertFalse(rule.observe(false, "not in range", 120.0, "relay open"), "Devices.reds: the device is not in its last list");
			assertFalse(rule.observe(false, "Target Gone", 120.0, "breach port ping"), "not streamed");
			assertFalse(rule.observe(false, "arrangement", 120.0, "x"), "whole words only");
			assertFalse(rule.observe(false, "insightful", 120.0, "x"));
			assertFalse(rule.observe(false, "out of range", Double.NaN, "relay open"), "V's place unknown: says nothing about 50 m");
			assertFalse(rule.observe(false, "out of range", -1.0, "x"));
		}

		assertFalse(rule.fallback());
		assertFalse(RangeRule.rangeRefusal("not in range"));
		assertTrue(RangeRule.rangeRefusal("out of range"));
		assertTrue(rule.allows(Double.NaN, true), "V's place unknown: the documented path still sends");
		assertFalse(new RangeRule("near").allows(Double.NaN, true), "the fallback does not");
	}

	@Test
	void thePropertyForcesOnePathForEveryRemoteAction() {
		RangeRule near = new RangeRule("near");
		assertTrue(near.fallback());
		assertFalse(near.allows(60.0, true));
		assertTrue(near.takeLine().contains("forced by -Dcyberblock.remoteRange=near"));
		near.reset();
		assertTrue(near.fallback(), "a new server starts on the forced path again");

		RangeRule streamed = new RangeRule(" Streamed ");
		assertFalse(streamed.observe(false, "out of range", 120.0, "x"), "the fallback stays off");
		assertTrue(streamed.allows(500.0, true));
		assertTrue(streamed.line().contains("forced by -Dcyberblock.remoteRange=streamed"), streamed.line());

		RangeRule other = new RangeRule("bogus");
		assertTrue(other.observe(false, "out of range", 120.0, "x"), "anything else is the default");
	}
}
