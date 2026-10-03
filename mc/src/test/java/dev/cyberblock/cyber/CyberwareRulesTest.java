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
	/** Plan retest2 section 1.7, copied by hand: the wire ids in their fixed order. */
	private static final List<String> PLAN = List.of(
		"mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws", "sandevistan", "berserk", "cyberdeck", "kerenzikov", "optical_camo", "subdermal_armor", "kiroshi_optics",
		"biomonitor", "second_heart", "ram_upgrade");

	/** Section 1.7's table: id, slot, kind, duration and cooldown in ticks. */
	private static final List<String> TABLE = List.of(
		"mantis_blades arms PASSIVE 0 0",
		"gorilla_arms arms PASSIVE 0 0",
		"monowire arms PASSIVE 0 0",
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
		"ram_upgrade frontal_cortex PASSIVE 0 0");

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
		Map<String, Integer> plan = Map.of("arms", 1, "legs", 1, "operating_system", 1, "nervous_system", 1,
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
		CyberwareRules.Installed deck = CyberwareRules.install(Map.of(), Cyberware.CYBERDECK);
		CyberwareRules.Installed sandy = CyberwareRules.install(deck.slots(), Cyberware.SANDEVISTAN);
		assertEquals(CyberwareRules.Install.REPLACED, sandy.outcome());
		assertSame(Cyberware.CYBERDECK, sandy.replaced());
		assertEquals("operating_system/0", sandy.slotKey());
		assertEquals(Map.of("operating_system/0", "sandevistan"), sandy.slots());
		// a removed implant leaves a gap that is free again; a slot holding an id nobody knows is free too
		Map<String, String> gap = new HashMap<>(Map.of("circulatory/0", "biomonitor", "circulatory/1", "second_heart"));
		gap.remove("circulatory/0");
		assertEquals("circulatory/0", CyberwareRules.install(gap, Cyberware.BIOMONITOR).slotKey());
		CyberwareRules.Installed stale = CyberwareRules.install(Map.of("eyes/0", "x-ray"), Cyberware.KIROSHI_OPTICS);
		assertEquals(CyberwareRules.Install.INSTALLED, stale.outcome());
		assertEquals(Map.of("eyes/0", "kiroshi_optics"), stale.slots());
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
	void activeOrderSelectionAndCycling() {
		Set<Cyberware> set = EnumSet.of(Cyberware.OPTICAL_CAMO, Cyberware.PROJECTILE_LAUNCH_SYSTEM, Cyberware.SANDEVISTAN,
			Cyberware.BIOMONITOR, Cyberware.KERENZIKOV, Cyberware.KIROSHI_OPTICS);
		// the HUD: every ACTIVE or AUTO implant in catalogue order
		assertEquals(List.of(Cyberware.PROJECTILE_LAUNCH_SYSTEM, Cyberware.SANDEVISTAN, Cyberware.KERENZIKOV, Cyberware.OPTICAL_CAMO,
			Cyberware.BIOMONITOR), CyberwareRules.activeOrder(set));
		// Y: only ACTIVE ones; the default is the first
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.defaultSelected(set, null));
		assertSame(Cyberware.OPTICAL_CAMO, CyberwareRules.defaultSelected(set, Cyberware.OPTICAL_CAMO));
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.defaultSelected(set, Cyberware.BIOMONITOR)); // AUTO is never selected
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.defaultSelected(set, Cyberware.BERSERK)); // not installed
		assertSame(Cyberware.SANDEVISTAN, CyberwareRules.next(set, Cyberware.PROJECTILE_LAUNCH_SYSTEM));
		assertSame(Cyberware.OPTICAL_CAMO, CyberwareRules.next(set, Cyberware.SANDEVISTAN));
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.next(set, Cyberware.OPTICAL_CAMO)); // wraps
		assertSame(Cyberware.PROJECTILE_LAUNCH_SYSTEM, CyberwareRules.next(set, null));
		assertNull(CyberwareRules.defaultSelected(EnumSet.of(Cyberware.BIOMONITOR), null));
		assertNull(CyberwareRules.next(EnumSet.noneOf(Cyberware.class), null));
		assertSame(Cyberware.BERSERK, CyberwareRules.next(EnumSet.of(Cyberware.BERSERK), Cyberware.BERSERK));
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
