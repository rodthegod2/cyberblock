package dev.cyberblock.cyber;

import static dev.cyberblock.cyber.PerkShop.Outcome.LOCKED;
import static dev.cyberblock.cyber.PerkShop.Outcome.OK;
import static dev.cyberblock.cyber.PerkShop.Outcome.OWNED;
import static dev.cyberblock.cyber.PerkShop.Outcome.TOO_POOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerkShopTest {
	/** Plan 2026-10-03 section 1.5, in branch (= purchase) order. */
	private static final List<String> PLAN = List.of(
		"iron_skin", "gorilla_arms", "painkiller", "juggernaut",
		"double_jump", "sandevistan", "quick_draw", "dodge",
		"tinkerer", "smart_arrows", "field_medic", "demolitions",
		"extra_ram", "cheap_hacks", "chain_hack", "overclock",
		"optical_camo", "headhunter", "cold_blood", "silent_steps"
	);

	@Test
	void perkTableMatchesThePlan() {
		assertEquals(PLAN, Arrays.stream(Perk.values()).map(Perk::id).toList());
		assertSame(Perk.CHAIN_HACK, Perk.byId("chain_hack"));
		assertNull(Perk.byId("blackwall"));
	}

	@Test
	void tiersCostsAndPrerequisites() {
		int[] costs = {3, 5, 8, 12};
		for (Perk p : Perk.values()) {
			assertEquals(p.ordinal() / 4, p.branch(), p.id());
			assertEquals(p.ordinal() % 4 + 1, p.tier(), p.id());
			assertEquals(costs[p.tier() - 1], p.cost(), p.id());
			assertEquals(p.tier() == 1 ? null : Perk.values()[p.ordinal() - 1], p.prerequisite(), p.id());
		}

		assertEquals("cool", Perk.BRANCHES[Perk.SILENT_STEPS.branch()]);
		assertEquals("intelligence", Perk.BRANCHES[Perk.EXTRA_RAM.branch()]);
	}

	@Test
	void firstTierNeedsOnlyLevels() {
		assertEquals(OK, PerkShop.check(Perk.EXTRA_RAM, Map.of(), 3));
		assertEquals(TOO_POOR, PerkShop.check(Perk.EXTRA_RAM, Map.of(), 2));
	}

	@Test
	void tiersInOrder() {
		assertEquals(LOCKED, PerkShop.check(Perk.CHEAP_HACKS, Map.of(), 100));
		assertEquals(OK, PerkShop.check(Perk.CHEAP_HACKS, Map.of("extra_ram", 1), 5));
		assertEquals(TOO_POOR, PerkShop.check(Perk.CHEAP_HACKS, Map.of("extra_ram", 1), 4));
		assertEquals(LOCKED, PerkShop.check(Perk.OVERCLOCK, Map.of("extra_ram", 1, "cheap_hacks", 1), 100));
		assertEquals(OK, PerkShop.check(Perk.OVERCLOCK, Map.of("extra_ram", 1, "cheap_hacks", 1, "chain_hack", 1), 12));
		// another branch's perks do not count, and rank 0 is not owned
		assertEquals(LOCKED, PerkShop.check(Perk.SANDEVISTAN, Map.of("iron_skin", 1), 100));
		assertEquals(LOCKED, PerkShop.check(Perk.SANDEVISTAN, Map.of("double_jump", 0), 100));
	}

	@Test
	void ownedOnce() {
		assertEquals(OWNED, PerkShop.check(Perk.IRON_SKIN, Map.of("iron_skin", 1), 100));
	}

	@Test
	void perksJsonListsAllTwentyInOrder() {
		JsonObject m = JsonParser.parseString(CyberWire.perks(Map.of("iron_skin", 1, "sandevistan", 3, "dodge", 0, "nonsense", 1))).getAsJsonObject();
		assertEquals("perks", m.get("t").getAsString());
		JsonArray list = m.getAsJsonArray("list");
		assertEquals(20, list.size());
		for (int i = 0; i < 20; i++) {
			JsonObject e = list.get(i).getAsJsonObject();
			assertEquals(PLAN.get(i), e.get("id").getAsString());
			int expected = PLAN.get(i).equals("iron_skin") || PLAN.get(i).equals("sandevistan") ? 1 : 0;
			assertEquals(expected, e.get("rank").getAsInt(), PLAN.get(i));
			assertEquals(2, e.size());
		}

		assertTrue(CyberWire.perks(Map.of()).startsWith("{\"t\":\"perks\",\"list\":[{\"id\":\"iron_skin\",\"rank\":0},{\"id\":\"gorilla_arms\",\"rank\":0}"));
	}
}
