package dev.cyberblock.client.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.npc.NpcWire;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 7-N1: the role screen's paging, words and colours that need no running game. */
class NpcScreenTest {
	@Test
	void pagesOfFiveRows() {
		assertEquals(1, NpcScreen.pages(0));
		assertEquals(1, NpcScreen.pages(5));
		assertEquals(2, NpcScreen.pages(6));
		assertEquals(7, NpcScreen.pages(NpcWire.MAX_ROWS));
		assertEquals(NpcScreen.height(1), NpcScreen.height(0), "room for one row when empty");
		assertTrue(NpcScreen.height(2) < NpcScreen.height(5));
		assertEquals(NpcScreen.height(5), NpcScreen.height(30), "never more than a page");
	}

	@Test
	void everyCodeTheServerSendsHasEnglishWords() {
		for (String code : List.of("buy", "sell", "sold_out", "install", "surgery", "installed", "take", "turn_in", "abandon", "drink", "ask", "heal",
			"repair", "repair_vehicle")) {
			assertNotEquals(code, NpcScreen.button(code).getString(), code);
		}

		for (String code : List.of("ok", "sold", "poor", "stock", "gone", "noitem", "busy", "taken", "done", "abandoned", "none", "installed", "recovering",
			"surgery_ok", "surgery_fail", "cancelled", "healed", "repaired", "unavailable", "kept", "drank")) {
			assertNotEquals(code, NpcScreen.result(code).getString(), code);
		}

		assertTrue(NpcScreen.good("surgery_ok"));
		assertFalse(NpcScreen.good("surgery_fail"));
		assertFalse(NpcScreen.good("poor"));
	}

	@Test
	void anUnnamedNpcIsCalledByItsRole() {
		NpcWire.Screen s = new NpcWire.Screen(1, "contracts", "", "cyberblock.role.fixer", "", 0, "gigs", List.of("gigs"), List.of(), "");
		assertEquals("Fixer", NpcScreen.title(s).getString());
		assertEquals("Venn, Fixer", NpcScreen.title(new NpcWire.Screen(1, "contracts", "Venn", "cyberblock.role.fixer", "", 0, "gigs", List.of("gigs"), List.of(), ""))
			.getString());
	}

	@Test
	void aRowIsNamedByItsKeyThenItsTextThenItsService() {
		NpcWire.Row drink = new NpcWire.Row("d0", "minecraft:honey_bottle", 1, "cyberblock.drink.neon_sour", "Neon Sour", "", 12, "drink", true);
		assertEquals("Neon Sour", NpcScreen.rowName(drink, net.minecraft.world.item.ItemStack.EMPTY).getString(), "the key's English");
		NpcWire.Row gig = new NpcWire.Row("c0", "", 0, "", "Claw Trimming", "", 260, "take", true);
		assertEquals("Claw Trimming", NpcScreen.rowName(gig, net.minecraft.world.item.ItemStack.EMPTY).getString());
		NpcWire.Row heal = new NpcWire.Row("heal", "", 3, "", "", "", 18, "heal", true);
		assertEquals("Heal 3 hearts", NpcScreen.rowName(heal, net.minecraft.world.item.ItemStack.EMPTY).getString());
	}
}
