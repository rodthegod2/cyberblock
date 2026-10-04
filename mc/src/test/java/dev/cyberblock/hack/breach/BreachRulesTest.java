package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 6-B1: the line rule, uploads as contiguous runs, the screen's progress guide and the strength table. */
class BreachRulesTest {
	@Test
	void lineRule() {
		int[] picks = {2, 17, 15};
		assertTrue(BreachRules.allowed(5, picks, 0, 4));
		assertFalse(BreachRules.allowed(5, picks, 0, 5));
		assertTrue(BreachRules.allowed(5, picks, 1, 22), "column of cell 2");
		assertFalse(BreachRules.allowed(5, picks, 1, 3));
		assertTrue(BreachRules.allowed(5, picks, 2, 19), "row of cell 17");
		assertTrue(BreachRules.allowed(5, picks, 3, 0), "column of cell 15");
		assertFalse(BreachRules.allowed(5, picks, 3, 25), "off the grid");
	}

	@Test
	void uploadNeedsAContiguousRun() {
		int[] buffer = {0, 3, 4, 2, 9, 9};
		assertTrue(BreachRules.uploaded(buffer, 4, new int[] {3, 4, 2}));
		assertFalse(BreachRules.uploaded(buffer, 3, new int[] {3, 4, 2}), "only the filled part counts");
		assertFalse(BreachRules.uploaded(buffer, 4, new int[] {3, 2}), "not a run");
		assertTrue(BreachRules.uploaded(buffer, 4, new int[] {0, 3}));
	}

	@Test
	void matchedIsTheStartedPart() {
		int[] buffer = {1, 3, 4};
		assertEquals(2, BreachRules.matched(buffer, 3, new int[] {3, 4, 5}));
		assertEquals(1, BreachRules.matched(buffer, 3, new int[] {4, 0}));
		assertEquals(0, BreachRules.matched(buffer, 3, new int[] {5, 5}));
		assertEquals(0, BreachRules.matched(buffer, 0, new int[] {1, 3}));
	}

	@Test
	void moreAndLongerIsStronger() {
		assertEquals(0, BreachRules.strength(List.of()));
		assertEquals(1, BreachRules.strength(List.of(2)));
		assertEquals(1, BreachRules.strength(List.of(3)));
		assertEquals(2, BreachRules.strength(List.of(4)));
		assertEquals(2, BreachRules.strength(List.of(2, 2)));
		assertEquals(2, BreachRules.strength(List.of(2, 2, 2)));
		assertEquals(3, BreachRules.strength(List.of(3, 4)));
		assertEquals(3, BreachRules.strength(List.of(2, 2, 3)));
	}
}
