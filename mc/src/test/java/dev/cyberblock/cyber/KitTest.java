package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KitTest {
	private static Map<String, Integer> counts() {
		return Kit.ENTRIES.stream().filter(e -> e.potion() == null).collect(Collectors.toMap(Kit.Entry::item, Kit.Entry::count, Integer::sum));
	}

	@Test
	void kitMatchesThePlan() {
		Map<String, Integer> c = counts();
		assertEquals(1, c.get("cyberblock:cyberdeck"));
		assertEquals(2, c.get("cyberblock:ram_shard"));
		for (Hack h : Hack.values()) {
			assertEquals(1, c.get("cyberblock:" + h.item()), h.item());
		}

		Map<String, Integer> vanilla = Map.ofEntries(
			Map.entry("minecraft:stone", 64), Map.entry("minecraft:glass", 64), Map.entry("minecraft:tnt", 8), Map.entry("minecraft:flint_and_steel", 1),
			Map.entry("minecraft:bow", 1), Map.entry("minecraft:arrow", 32), Map.entry("minecraft:shield", 1), Map.entry("minecraft:iron_sword", 1),
			Map.entry("minecraft:lead", 2), Map.entry("minecraft:fishing_rod", 1), Map.entry("minecraft:wind_charge", 8), Map.entry("minecraft:ender_pearl", 8),
			Map.entry("minecraft:elytra", 1), Map.entry("minecraft:firework_rocket", 16),
			Map.entry("minecraft:zombie_spawn_egg", 4), Map.entry("minecraft:skeleton_spawn_egg", 2), Map.entry("minecraft:creeper_spawn_egg", 2),
			Map.entry("cyberblock:mantis_blade", 1), Map.entry("cyberblock:gorilla_gauntlet", 1));
		vanilla.forEach((item, count) -> assertEquals(count, c.get(item), item));
		// nothing else
		assertEquals(2 + Hack.values().length + vanilla.size(), c.size());
		List<String> potions = Kit.ENTRIES.stream().filter(e -> e.potion() != null).map(e -> e.item() + " " + e.potion() + " " + e.count()).toList();
		assertEquals(List.of("minecraft:potion minecraft:swiftness 1", "minecraft:potion minecraft:invisibility 1", "minecraft:potion minecraft:leaping 1"), potions);
	}

	@Test
	void everyCountFitsOneStack() {
		for (Kit.Entry e : Kit.ENTRIES) {
			assertTrue(e.count() >= 1 && e.count() <= 64, e.item());
		}

		assertTrue(counts().get("minecraft:ender_pearl") <= 16);
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void killXp() {
		assertEquals(5, Kills.xp(false));
		assertEquals(10, Kills.xp(true));
	}

	@Test
	void killedParse() {
		Kills.Killed k = Kills.parse(json("{\"t\":\"killed\",\"target\":\"123456789012\",\"kind\":\"human\",\"hostile\":true}"));
		assertNotNull(k);
		assertEquals(123456789012L, k.target());
		assertTrue(k.hostile());
		assertEquals(false, k.vehicle());
		Kills.Killed v = Kills.parse(json("{\"t\":\"killed\",\"target\":\"7\",\"kind\":\"vehicle\",\"hostile\":false}"));
		assertNotNull(v);
		assertTrue(v.vehicle());
		assertNull(Kills.parse(json("{\"target\":\"7\",\"kind\":\"dog\",\"hostile\":false}")));
		assertNull(Kills.parse(json("{\"target\":\"0\",\"kind\":\"human\",\"hostile\":false}")));
		assertNull(Kills.parse(json("{\"target\":7,\"kind\":\"human\",\"hostile\":\"no\"}")));
		assertNull(Kills.parse(json("{\"target\":\"x\",\"kind\":\"human\",\"hostile\":false}")));
		assertNull(Kills.parse(json("{\"kind\":\"human\",\"hostile\":false}")));
		assertNull(Kills.parse(json("{\"target\":\"7\",\"kind\":\"human\"}")));
	}
}
