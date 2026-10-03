package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
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
		assertEquals(2, c.get("cyberblock:ram_shard"));
		for (Hack h : Hack.values()) {
			assertEquals(1, c.get("cyberblock:" + h.item()), h.item());
		}

		Map<String, Integer> vanilla = Map.ofEntries(
			Map.entry("minecraft:stone", 64), Map.entry("minecraft:glass", 64), Map.entry("minecraft:tnt", 8), Map.entry("minecraft:flint_and_steel", 1),
			Map.entry("minecraft:bow", 1), Map.entry("minecraft:arrow", 32), Map.entry("minecraft:shield", 1), Map.entry("minecraft:iron_sword", 1),
			Map.entry("minecraft:lead", 2), Map.entry("minecraft:fishing_rod", 1), Map.entry("minecraft:wind_charge", 8), Map.entry("minecraft:ender_pearl", 8),
			Map.entry("minecraft:elytra", 1), Map.entry("minecraft:firework_rocket", 16),
			Map.entry("minecraft:zombie_spawn_egg", 4), Map.entry("minecraft:skeleton_spawn_egg", 2), Map.entry("minecraft:creeper_spawn_egg", 2));
		vanilla.forEach((item, count) -> assertEquals(count, c.get(item), item));
		// plan retest2 C4 / retest3 1.8: a basic set installed at once (the deck and the Sandevistan together), two ACTIVE
		// implants to install, and the old weapons gone
		List<String> install = Kit.ENTRIES.stream().filter(e -> e.slot() == Kit.INSTALL).map(Kit.Entry::item).toList();
		assertEquals(List.of("cyberblock:cyberdeck", "cyberblock:sandevistan", "cyberblock:kiroshi_optics", "cyberblock:reinforced_tendons",
			"cyberblock:biomonitor"), install);
		for (String implant : List.of("cyberblock:optical_camo", "cyberblock:projectile_launch_system")) {
			assertEquals(Kit.MAIN, slotOf(implant), implant);
			assertEquals(1, c.get(implant), implant);
		}

		assertNull(c.get("cyberblock:mantis_blade"));
		assertNull(c.get("cyberblock:gorilla_gauntlet"));
		// nothing else
		assertEquals(1 + Hack.values().length + vanilla.size() + install.size() + 2, c.size());
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

	@Test
	void hotbarHoldsWhatIsUsedFirstAndNoCyberItems() {
		Map<Integer, String> hotbar = new HashMap<>();
		for (Kit.Entry e : Kit.ENTRIES) {
			if (e.slot() >= 0 && e.slot() <= 8) {
				assertNull(hotbar.put(e.slot(), e.item()), "two kit stacks for hotbar slot " + e.slot());
				assertTrue(!e.item().startsWith("cyberblock:"), e.item() + " must stay off the hotbar");
			}
		}

		assertEquals(Map.of(0, "minecraft:iron_sword", 1, "minecraft:bow", 2, "minecraft:stone", 3, "minecraft:tnt",
			4, "minecraft:flint_and_steel", 5, "minecraft:ender_pearl", 6, "minecraft:firework_rocket", 7, "minecraft:lead",
			8, "minecraft:fishing_rod"), hotbar);
		assertEquals(Kit.OFFHAND, slotOf("minecraft:shield"));
		assertEquals(Kit.CHEST, slotOf("minecraft:elytra"));
		assertEquals(Kit.MAIN, slotOf("minecraft:arrow")); // the bow finds them anywhere in the inventory
		assertEquals(Kit.INSTALL, slotOf("cyberblock:cyberdeck"));
		for (Kit.Entry e : Kit.ENTRIES) {
			assertTrue(e.slot() == Kit.MAIN || e.slot() == Kit.INSTALL || e.slot() == Kit.CHEST || e.slot() == Kit.OFFHAND || e.slot() >= 0 && e.slot() <= 8, e.item());
		}
	}

	private static int slotOf(final String item) {
		return Kit.ENTRIES.stream().filter(e -> e.item().equals(item)).findFirst().orElseThrow().slot();
	}

	/** Where each kit stack lands with Kit.target, given the slots already taken (Minecraft's numbering: 0..35, 38 chest, 40 offhand). */
	private static Map<String, Integer> place(final boolean[] taken) {
		Map<String, Integer> placed = new HashMap<>();
		for (Kit.Entry e : Kit.ENTRIES) {
			int slot = Kit.target(e.slot(), s -> !taken[s]);
			if (slot >= 0) {
				taken[slot] = true;
			}

			placed.put(e.item() + (e.potion() == null ? "" : " " + e.potion()), slot);
		}

		return placed;
	}

	/**
	 * The worst case: every INSTALL entry finds its slot taken and goes to the main inventory too; the whole kit still fits
	 * the 27 main slots, so nothing is dropped at his feet.
	 */
	@Test
	void aFreshInventoryTakesTheWholeKitInPlace() {
		Map<String, Integer> placed = place(new boolean[41]);
		for (Kit.Entry e : Kit.ENTRIES) {
			int slot = placed.get(e.item() + (e.potion() == null ? "" : " " + e.potion()));
			if (e.slot() == Kit.MAIN || e.slot() == Kit.INSTALL) {
				assertTrue(slot >= 9 && slot < 36, e.item() + " went to " + slot);
			} else {
				assertEquals(e.slot(), slot, e.item());
			}
		}

		assertTrue(Kit.ENTRIES.stream().filter(e -> e.slot() == Kit.MAIN || e.slot() == Kit.INSTALL).count() <= 27);
	}

	@Test
	void takenSlotsSendTheStackToTheMainInventoryThenToVanilla() {
		boolean[] taken = new boolean[41];
		for (int s = 0; s < 9; s++) {
			taken[s] = true;
		}

		taken[Kit.OFFHAND] = true;
		assertEquals(9, Kit.target(0, s -> !taken[s]));
		assertEquals(9, Kit.target(Kit.OFFHAND, s -> !taken[s]));
		assertEquals(9, Kit.target(Kit.MAIN, s -> !taken[s]));
		for (int s = 9; s < 36; s++) {
			taken[s] = true;
		}

		assertEquals(-1, Kit.target(4, s -> !taken[s]));
		assertEquals(Kit.CHEST, Kit.target(Kit.CHEST, s -> !taken[s]));
		// a main-inventory stack never takes a free hotbar slot itself (vanilla may, once the main inventory is full)
		taken[3] = false;
		assertEquals(-1, Kit.target(Kit.MAIN, s -> !taken[s]));
		assertEquals(3, Kit.target(3, s -> !taken[s]));
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
