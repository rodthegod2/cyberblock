package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Megaplan task 1-C1: CyberNet hands every cyber act type to the net core (ServerNet.onAct), so none is dropped. */
class CyberNetTest {
	@Test
	void everyActTypeHasAHandler() {
		Set<String> types = Arrays.stream(CyberActs.class.getFields())
			.filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
			.map(CyberNetTest::value)
			.collect(Collectors.toSet());
		assertEquals(Set.of("scan", "hack", "perk", "ware", "fire", "sandevistan"), types);
		assertEquals(types, CyberNet.ACTS.keySet());
	}

	private static String value(final Field f) {
		try {
			return (String) f.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}
}
