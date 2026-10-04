package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CyberwareRulesTest {
	/** Plan retest2 section 1.7, copied by hand: the wire ids in their fixed order, then megaplan section 1.17's firewall (Phase 6) and netrunner (Phase 8). */
	private static final List<String> PLAN = List.of(
		"mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws", "sandevistan", "berserk", "cyberdeck", "kerenzikov", "optical_camo", "subdermal_armor", "kiroshi_optics",
		"biomonitor", "second_heart", "ram_upgrade", "firewall", "netrunner");

	/** Section 1.7's table (retest3 section 1.8: the three melee arms are ACTIVE on L): id, slot, kind, duration and cooldown in ticks. */
	private static final List<String> TABLE = List.of(
		"mantis_blades arms ACTIVE 0 120",
		"gorilla_arms arms ACTIVE 0 160",
		"monowire arms ACTIVE 0 100",
		"projectile_launch_system arms ACTIVE 0 160",
		"reinforced_tendons legs PASSIVE 0 0",
		"fortified_ankles legs PASSIVE 0 0",
		"lynx_paws legs PASSIVE 0 0",
		"sandevistan operating_system ACTIVE 120 500",
		"berserk operating_system ACTIVE 160 800",
		"cyberdeck operating_system PASSIVE 0 0",
		"kerenzikov nervous_system AUTO 40 200",
		"optical_camo integumentary ACTIVE 200 600",
		"subdermal_armor integumentary PASSIVE 0 0",
		"kiroshi_optics eyes PASSIVE 0 0",
		"biomonitor circulatory AUTO 0 1800",
		"second_heart circulatory AUTO 0 6000",
		"ram_upgrade frontal_cortex PASSIVE 0 0",
		"firewall frontal_cortex AUTO 0 1200",
		"netrunner nervous_system PASSIVE 0 0");

	@Test
	void idsAreThePlansInItsOrder() {
		assertEquals(PLAN, Arrays.stream(Cyberware.values()).map(Cyberware::id).toList());
		assertSame(Cyberware.SECOND_HEART, Cyberware.byId("second_heart"));
		assertNull(Cyberware.byId("SECOND_HEART"));
		assertNull(Cyberware.byId("blackwall"));
	}

	@Test
	void tableMatchesThePlan() {
		List<String> table = Arrays.stream(Cyberware.values())
			.map(c -> c.id() + " " + c.slot().id() + " " + c.kind() + " " + c.durationTicks() + " " + c.cooldownTicks()).toList();
		assertEquals(TABLE, table);
	}

	@Test
	void slotsAndCapacities() {
		Map<String, Integer> plan = Map.of("arms", 1, "legs", 1, "operating_system", 2, "nervous_system", 1,
			"integumentary", 2, "eyes", 1, "circulatory", 2, "frontal_cortex", 1);
		assertEquals(8, CyberSlot.values().length);
		for (CyberSlot slot : CyberSlot.values()) {
			assertEquals(plan.get(slot.id()), slot.capacity(), slot.id());
			assertEquals("cyberblock.cyberware.slot." + slot.id(), slot.langKey());
		}

		assertEquals("integumentary/1", CyberSlot.INTEGUMENTARY.key(1));
	}

	@Test
	void installTakesTheFirstFreeSlot() {
		CyberwareRules.Installed first = CyberwareRules.install(Map.of(), Cyberware.BIOMONITOR);
		assertEquals(CyberwareRules.Install.INSTALLED, first.outcome());
		assertEquals("circulatory/0", first.slotKey());
		assertEquals(Map.of("circulatory/0", "biomonitor"), first.slots());
		CyberwareRules.Installed second = CyberwareRules.install(first.slots(), Cyberware.SECOND_HEART);
		assertEquals(CyberwareRules.Install.INSTALLED, second.outcome());
		assertEquals("circulatory/1", second.slotKey());
		assertNull(second.replaced());
		assertEquals(Map.of("circulatory/0", "biomonitor", "circulatory/1", "second_heart"), second.slots());
	}

	@Test
	void aFullSlotReplacesItsFirstImplant() {
		CyberwareRules.Installed pls = CyberwareRules.install(Map.of(), Cyberware.PROJECTILE_LAUNCH_SYSTEM);
		CyberwareRules.Installed mantis = CyberwareRules.install(pls.slots(), Cyberware.MANTIS_BLADES);
		assertEquals(CyberwareRules.Install.REPLACED, mantis.outcome());
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, mantis.replaced());
		assertEquals("arms/0", mantis.slotKey());
		assertEquals(Map.of("arms/0", "mantis_blades"), mantis.slots());
		// a removed implant leaves a gap that is free again; a slot holding an id nobody knows is free too
		Map<String, String> gap = new HashMap<>(Map.of("circulatory/0", "biomonitor", "circulatory/1", "second_heart"));
		gap.remove("circulatory/0");
		assertEquals("circulatory/0", CyberwareRules.install(gap, Cyberware.BIOMONITOR).slotKey());
		CyberwareRules.Installed stale = CyberwareRules.install(Map.of("eyes/0", "x-ray"), Cyberware.KIROSHI_OPTICS);
		assertEquals(CyberwareRules.Install.INSTALLED, stale.outcome());
		assertEquals(Map.of("eyes/0", "kiroshi_optics"), stale.slots());
	}

	/** Plan retest3 section 1.8 (F9): two Operating System positions, the deck in 0, Sandevistan or Berserk in 1. */
	@Test
	void theDeckAndASandevistanRunTogether() {
		assertEquals(0, CyberSlot.position(Cyberware.CYBERDECK));
		assertEquals(1, CyberSlot.position(Cyberware.SANDEVISTAN));
		assertEquals(1, CyberSlot.position(Cyberware.BERSERK));
		assertEquals(-1, CyberSlot.position(Cyberware.KIROSHI_OPTICS));
		// Sandevistan first: it still takes position 1, and the deck then goes to 0
		CyberwareRules.Installed sandy = CyberwareRules.install(Map.of(), Cyberware.SANDEVISTAN);
		assertEquals("operating_system/1", sandy.slotKey());
		CyberwareRules.Installed deck = CyberwareRules.install(sandy.slots(), Cyberware.CYBERDECK);
		assertEquals(CyberwareRules.Install.INSTALLED, deck.outcome());
		assertEquals(Map.of("operating_system/0", "cyberdeck", "operating_system/1", "sandevistan"), deck.slots());
		// Berserk replaces the Sandevistan, never the deck
		CyberwareRules.Installed berserk = CyberwareRules.install(deck.slots(), Cyberware.BERSERK);
		assertEquals(CyberwareRules.Install.REPLACED, berserk.outcome());
		assertSame(Cyberware.SANDEVISTAN, berserk.replaced());
		assertEquals(Map.of("operating_system/0", "cyberdeck", "operating_system/1", "berserk"), berserk.slots());
		// an unknown id in a fixed position counts as free
		CyberwareRules.Installed stale = CyberwareRules.install(Map.of("operating_system/1", "x-ray"), Cyberware.SANDEVISTAN);
		assertEquals(CyberwareRules.Install.INSTALLED, stale.outcome());
	}

	@Test
	void normalizeMovesAnOldSavesSandevistanToItsPosition() {
		assertEquals(Map.of("operating_system/1", "sandevistan", "eyes/0", "kiroshi_optics"),
			CyberwareRules.normalize(Map.of("operating_system/0", "sandevistan", "eyes/0", "kiroshi_optics")));
		assertEquals(Map.of("operating_system/1", "berserk"), CyberwareRules.normalize(Map.of("operating_system/0", "berserk")));
		// already right (or nothing to move): the very same map
		Map<String, String> right = Map.of("operating_system/0", "cyberdeck", "operating_system/1", "sandevistan");
		assertSame(right, CyberwareRules.normalize(right));
		Map<String, String> none = Map.of("arms/0", "monowire");
		assertSame(none, CyberwareRules.normalize(none));
	}

	@Test
	void installingTwiceIsAlready() {
		Map<String, String> slots = CyberwareRules.install(Map.of(), Cyberware.KIROSHI_OPTICS).slots();
		CyberwareRules.Installed again = CyberwareRules.install(slots, Cyberware.KIROSHI_OPTICS);
		assertEquals(CyberwareRules.Install.ALREADY, again.outcome());
		assertEquals(slots, again.slots());
		assertNull(again.replaced());
	}

	@Test
	void installedReadsKnownIdsOnly() {
		Set<Cyberware> set = CyberwareRules.installed(Map.of("arms/0", "monowire", "eyes/0", "x-ray", "legs/0", "lynx_paws"));
		assertEquals(EnumSet.of(Cyberware.MONOWIRE, Cyberware.LYNX_PAWS), set);
	}

	@Test
	void activeOrderIsTheHudRow() {
		Set<Cyberware> set = EnumSet.of(Cyberware.OPTICAL_CAMO, Cyberware.PROJECTILE_LAUNCH_SYSTEM, Cyberware.SANDEVISTAN,
			Cyberware.BIOMONITOR, Cyberware.KERENZIKOV, Cyberware.KIROSHI_OPTICS);
		// the HUD: every ACTIVE or AUTO implant in catalogue order
		assertEquals(List.of(Cyberware.PROJECTILE_LAUNCH_SYSTEM, Cyberware.SANDEVISTAN, Cyberware.KERENZIKOV, Cyberware.OPTICAL_CAMO,
			Cyberware.BIOMONITOR), CyberwareRules.activeOrder(set));
	}

	/** Plan retest3 section 1.7: Y fires the Operating System implant, L the Arms implant, O the camo; nothing else has a key. */
	@Test
	void eachFireKeyFiresItsOwnPlace() {
		Set<Cyberware> set = EnumSet.of(Cyberware.OPTICAL_CAMO, Cyberware.PROJECTILE_LAUNCH_SYSTEM, Cyberware.SANDEVISTAN,
			Cyberware.CYBERDECK, Cyberware.BIOMONITOR, Cyberware.KERENZIKOV);
		assertSame(Cyberware.SANDEVISTAN, CyberwareRules.forTrigger(set, CyberwareRules.Trigger.OS));
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.forTrigger(set, CyberwareRules.Trigger.ARMS));
		assertSame(Cyberware.OPTICAL_CAMO, CyberwareRules.forTrigger(set, CyberwareRules.Trigger.CAMO));
		assertSame(Cyberware.BERSERK, CyberwareRules.forTrigger(EnumSet.of(Cyberware.BERSERK, Cyberware.CYBERDECK), CyberwareRules.Trigger.OS));
		// the deck is passive: alone it gives Y nothing to fire
		assertNull(CyberwareRules.forTrigger(EnumSet.of(Cyberware.CYBERDECK), CyberwareRules.Trigger.OS));
		assertNull(CyberwareRules.forTrigger(EnumSet.noneOf(Cyberware.class), CyberwareRules.Trigger.CAMO));
		for (Cyberware c : Cyberware.values()) {
			CyberwareRules.Trigger t = CyberwareRules.Trigger.of(c);
			assertEquals(c.kind() == Cyberware.Kind.ACTIVE, t != null, c.id());
		}

		assertEquals("key.cyberblock.activate", CyberwareRules.Trigger.OS.keyName());
		assertEquals("key.cyberblock.arms", CyberwareRules.Trigger.ARMS.keyName());
		assertEquals("key.cyberblock.camo", CyberwareRules.Trigger.CAMO.keyName());
		assertEquals("arms", CyberwareRules.Trigger.ARMS.id());
	}

	/**
	 * Megaplan section 1.17: the firewall comes after the 17 of retest2, has no key (AUTO since Phase 8: it fires by itself on a player
	 * hack), and takes the frontal cortex from the RAM upgrade.
	 */
	@Test
	void theFirewallIsAppendedAndSharesTheFrontalCortex() {
		assertEquals(17, Cyberware.FIREWALL.ordinal());
		assertTrue(Cyberware.values().length <= CyberwareRules.MAX_IDS);
		assertSame(Cyberware.FIREWALL, Cyberware.byId("firewall"));
		assertNull(CyberwareRules.Trigger.of(Cyberware.FIREWALL));
		assertEquals(0, CyberwareRules.firewallRank(EnumSet.of(Cyberware.RAM_UPGRADE)));
		assertEquals(1, CyberwareRules.firewallRank(EnumSet.of(Cyberware.FIREWALL, Cyberware.CYBERDECK)));
		CyberwareRules.Installed in = CyberwareRules.install(Map.of("frontal_cortex/0", "ram_upgrade"), Cyberware.FIREWALL);
		assertEquals(CyberwareRules.Install.REPLACED, in.outcome());
		assertSame(Cyberware.RAM_UPGRADE, in.replaced());
		assertEquals("firewall", in.slots().get("frontal_cortex/0"));
	}

	/** Megaplan 8-X1: a charged Firewall blocks one player hack, then recharges 60 s; the HUD shows it (AUTO). */
	@Test
	void theFirewallBlocksWhenChargedThenRecharges() {
		Set<Cyberware> with = EnumSet.of(Cyberware.FIREWALL);
		assertEquals(1200, Cyberware.FIREWALL.cooldownTicks());
		assertTrue(CyberwareRules.firewallBlocks(with, CyberwareRules.Timer.NONE, 100L));
		assertFalse(CyberwareRules.firewallBlocks(EnumSet.of(Cyberware.RAM_UPGRADE), CyberwareRules.Timer.NONE, 100L));
		CyberwareRules.Timer fired = CyberwareRules.Timer.fire(100L, 0, Cyberware.FIREWALL.cooldownTicks());
		assertFalse(CyberwareRules.firewallBlocks(with, fired, 101L));
		assertFalse(CyberwareRules.firewallBlocks(with, fired, 1299L));
		assertTrue(CyberwareRules.firewallBlocks(with, fired, 1300L));
		assertEquals(List.of(Cyberware.FIREWALL), CyberwareRules.activeOrder(with));
	}

	/** Megaplan section 1.17 (Phase 8): the netrunner comes after the firewall, is passive, and takes the nervous system from the Kerenzikov. */
	@Test
	void theNetrunnerIsAppendedAndSharesTheNervousSystem() {
		assertEquals(Cyberware.values().length - 1, Cyberware.NETRUNNER.ordinal());
		assertTrue(Cyberware.values().length <= CyberwareRules.MAX_IDS);
		assertSame(Cyberware.NETRUNNER, Cyberware.byId("netrunner"));
		assertNull(CyberwareRules.Trigger.of(Cyberware.NETRUNNER));
		assertTrue(CyberwareRules.netrunnerResists(EnumSet.of(Cyberware.NETRUNNER, Cyberware.FIREWALL)));
		assertFalse(CyberwareRules.netrunnerResists(EnumSet.of(Cyberware.FIREWALL)));
		CyberwareRules.Installed in = CyberwareRules.install(Map.of("nervous_system/0", "kerenzikov", "frontal_cortex/0", "firewall"), Cyberware.NETRUNNER);
		assertEquals(CyberwareRules.Install.REPLACED, in.outcome());
		assertSame(Cyberware.KERENZIKOV, in.replaced());
		assertEquals("netrunner", in.slots().get("nervous_system/0"));
		assertEquals("firewall", in.slots().get("frontal_cortex/0"));
	}

	@Test
	void theSandevistanPerkUpgradesTheImplant() {
		assertEquals(120, CyberwareRules.durationTicks(Cyberware.SANDEVISTAN, false));
		assertEquals(500, CyberwareRules.cooldownTicks(Cyberware.SANDEVISTAN, false));
		assertEquals(160, CyberwareRules.durationTicks(Cyberware.SANDEVISTAN, true));
		assertEquals(360, CyberwareRules.cooldownTicks(Cyberware.SANDEVISTAN, true));
		assertEquals(160, CyberwareRules.durationTicks(Cyberware.BERSERK, true));
		assertEquals(800, CyberwareRules.cooldownTicks(Cyberware.BERSERK, true));
		// a slowing implant's 6 s run at 10 TPS: 60 game ticks; others keep their ticks
		assertEquals(60, CyberwareRules.gameTicks(120, true));
		assertEquals(20, CyberwareRules.gameTicks(40, true));
		assertEquals(160, CyberwareRules.gameTicks(160, false));
	}

	@Test
	void migrationHandsOutTheImplantsThePerksNowUpgrade() {
		assertEquals(List.of(), CyberwareRules.migration(p -> false));
		assertEquals(List.of(Cyberware.REINFORCED_TENDONS, Cyberware.SANDEVISTAN, Cyberware.OPTICAL_CAMO), CyberwareRules.migration(p -> true));
		assertEquals(List.of(Cyberware.OPTICAL_CAMO), CyberwareRules.migration(p -> p == Perk.OPTICAL_CAMO || p == Perk.IRON_SKIN));
	}

	@Test
	void biomonitorBelowThirtyPercent() {
		assertTrue(CyberwareRules.biomonitor(6.0F, 20.0F));
		assertTrue(CyberwareRules.biomonitor(1.0F, 20.0F));
		assertFalse(CyberwareRules.biomonitor(6.5F, 20.0F));
		assertFalse(CyberwareRules.biomonitor(0.0F, 20.0F)); // dead is Second Heart's business
		assertTrue(CyberwareRules.biomonitor(8.4F, 28.0F)); // iron skin's 28 max health: 30 % is 8.4
	}

	@Test
	void chargeJumpNeedsAHoldAndAQuickTakeOff() {
		assertTrue(CyberwareRules.chargeJump(10, 0));
		assertTrue(CyberwareRules.chargeJump(40, 4));
		assertFalse(CyberwareRules.chargeJump(9, 0)); // a tap
		assertFalse(CyberwareRules.chargeJump(10, 5)); // took off too late: a fall, not a jump
		assertFalse(CyberwareRules.chargeJump(10, -1));
	}

	@Test
	void timerPhases() {
		// fired at 100: active 60 ticks (a slowed Sandevistan), then 500 ticks cooling down
		CyberwareRules.Timer t = CyberwareRules.Timer.fire(100L, 60, 500);
		assertEquals(new CyberwareRules.Timer(100L, 160L, 660L), t);
		assertTrue(t.active(100L) && t.active(159L) && !t.active(160L));
		assertFalse(t.ready(659L));
		assertTrue(t.ready(660L));
		assertEquals(1.0F, t.activeFraction(100L));
		assertEquals(0.5F, t.activeFraction(130L));
		assertEquals(0.0F, t.activeFraction(160L));
		assertEquals(1.0F, t.remainingFraction(100L)); // while active the whole cooldown is still ahead
		assertEquals(1.0F, t.remainingFraction(160L));
		assertEquals(0.5F, t.remainingFraction(410L));
		assertEquals(0.0F, t.remainingFraction(660L));
		assertEquals(25L, t.secondsLeft(160L));
		assertEquals(1L, t.secondsLeft(659L));
		assertEquals(0L, t.secondsLeft(660L));
		// an instant implant (no duration) cools down from the moment it fires
		CyberwareRules.Timer pls = CyberwareRules.Timer.fire(0L, 0, 160);
		assertFalse(pls.active(0L));
		assertEquals(0.5F, pls.remainingFraction(80L));
		assertEquals(CyberwareRules.Timer.NONE.readyAt(), 0L);
		assertTrue(CyberwareRules.Timer.NONE.ready(0L));
	}

	/** Camo broken by an attack: the effect ends now and the full cooldown starts now, as when it runs out. */
	@Test
	void stopEndsTheActivePartAndStartsTheCooldown() {
		CyberwareRules.Timer camo = CyberwareRules.Timer.fire(1000L, 200, 600);
		CyberwareRules.Timer broken = camo.stop(1050L);
		assertFalse(broken.active(1050L));
		assertEquals(new CyberwareRules.Timer(1000L, 1050L, 1650L), broken);
		assertEquals(1.0F, broken.remainingFraction(1050L));
		assertEquals(camo, camo.stop(5000L)); // already over: nothing changes
	}
}
