package dev.cyberblock.mode.pvphack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.Ram;
import dev.cyberblock.hack.breach.BreachContent;
import dev.cyberblock.mode.pvphack.PvpHackRules.Party;
import dev.cyberblock.mode.pvphack.PvpHackRules.Refusal;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan 8-X1: who may be hacked, the order of the refusals, costs and cooldowns, the counterplay and the netrunner's resistance. */
class PvpHackRulesTest {
	private static final Party IN = new Party(true, false, false, false);
	private static final Party GUEST_OUT = new Party(false, false, false, false);

	private static Refusal check(final Hack hack, final Party hacker, final Party target) {
		return PvpHackRules.check(hack, false, hacker, target, true, 8, Ram.cost(hack, false), false);
	}

	/** The opt-in rule: PvP on, or a Cyberpunk-linked player inside a war zone; never a Minecraft-only guest who has not opted in. */
	@Test
	void optInMatrix() {
		for (boolean pvp : new boolean[]{false, true}) {
			for (boolean war : new boolean[]{false, true}) {
				for (boolean linked : new boolean[]{false, true}) {
					boolean expected = pvp || war && linked;
					assertEquals(expected, PvpHackRules.targetable(pvp, war, linked), "pvp " + pvp + " war " + war + " linked " + linked);
				}
			}
		}

		// the guest in a war zone who did not opt in stays out
		assertFalse(PvpHackRules.targetable(false, true, false));
		assertEquals(Refusal.TARGET_OUT, check(Hack.SHORT_CIRCUIT, IN, new Party(false, true, false, false)));
		assertEquals(Refusal.NONE, check(Hack.SHORT_CIRCUIT, IN, new Party(false, true, true, false)));
		assertEquals(Refusal.NONE, check(Hack.SHORT_CIRCUIT, IN, IN));
		assertEquals(Refusal.TARGET_OUT, check(Hack.SHORT_CIRCUIT, IN, GUEST_OUT));
	}

	/** The hacker is held to the same rule: nobody hacks into a fight he cannot be hacked back in. */
	@Test
	void theHackerMustBeInTheFightToo() {
		assertEquals(Refusal.YOU_OUT, check(Hack.PING, GUEST_OUT, IN));
		assertEquals(Refusal.YOU_OUT, check(Hack.PING, new Party(false, true, false, false), IN));
		assertEquals(Refusal.NONE, check(Hack.PING, new Party(false, true, true, false), IN));
	}

	@Test
	void onlyTheSixPlayerChipsWork() {
		assertEquals(EnumSet.of(Hack.SHORT_CIRCUIT, Hack.REBOOT_OPTICS, Hack.CRIPPLE_MOVEMENT, Hack.PING, Hack.OVERHEAT, Hack.MEMORY_WIPE),
			PvpHackRules.CHIPS);
		for (Hack hack : Hack.values()) {
			assertEquals(PvpHackRules.CHIPS.contains(hack) ? Refusal.NONE : Refusal.NOT_A_PLAYER_CHIP, check(hack, IN, IN), hack.wire());
		}
	}

	/** Refusals come in a fixed order, so the player hears the first thing to fix. */
	@Test
	void refusalOrder() {
		int cost = Ram.cost(Hack.OVERHEAT, false);
		assertEquals(Refusal.SELF, PvpHackRules.check(Hack.OVERHEAT, true, IN, IN, true, 8, cost, false));
		assertEquals(Refusal.NOT_A_PLAYER_CHIP, PvpHackRules.check(Hack.CONTAGION, false, GUEST_OUT, GUEST_OUT, false, 0, cost, true));
		assertEquals(Refusal.NO_DECK, PvpHackRules.check(Hack.OVERHEAT, false, GUEST_OUT, GUEST_OUT, false, 0, cost, true));
		assertEquals(Refusal.YOU_OUT, PvpHackRules.check(Hack.OVERHEAT, false, GUEST_OUT, GUEST_OUT, true, 0, cost, true));
		Party shielded = new Party(true, false, false, true);
		assertEquals(Refusal.TARGET_SHIELDED, PvpHackRules.check(Hack.OVERHEAT, false, IN, shielded, true, 0, cost, true));
		assertEquals(Refusal.TARGET_OUT, PvpHackRules.check(Hack.OVERHEAT, false, IN, GUEST_OUT, true, 0, cost, true));
		assertEquals(Refusal.BUSY, PvpHackRules.check(Hack.OVERHEAT, false, IN, IN, true, 0, cost, true));
		assertEquals(Refusal.NO_RAM, PvpHackRules.check(Hack.OVERHEAT, false, IN, IN, true, cost - 1, cost, false));
		assertEquals(Refusal.NONE, PvpHackRules.check(Hack.OVERHEAT, false, IN, IN, true, cost, cost, false));
	}

	/** RAM costs are the chips' own (Cheap Hacks applies); each player chip recharges longer than on an NPC. */
	@Test
	void costsAndCooldowns() {
		for (Hack hack : PvpHackRules.CHIPS) {
			assertTrue(PvpHackRules.cooldownTicks(hack) > hack.cooldownTicks(), hack.wire());
			assertTrue(PvpHackRules.cooldownTicks(hack) <= 20 * 20, hack.wire());
			assertTrue(Ram.cost(hack, false) >= 1);
		}

		assertEquals(Set.of(200, 300, 100, 240, 400), Set.copyOf(PvpHackRules.CHIPS.stream().map(PvpHackRules::cooldownTicks).toList()));
		assertEquals(3, Ram.cost(Hack.SHORT_CIRCUIT, false));
		assertEquals(2, Ram.cost(Hack.SHORT_CIRCUIT, true));
		assertEquals(Hack.CONTAGION.cooldownTicks(), PvpHackRules.cooldownTicks(Hack.CONTAGION));
	}

	/** The netrunner implant resists: every effect lasts half as long (never under half a second); damage stays. */
	@Test
	void netrunnerShortensEffects() {
		for (Hack hack : PvpHackRules.CHIPS) {
			PvpHackRules.Effect full = PvpHackRules.effect(hack, false);
			PvpHackRules.Effect resisted = PvpHackRules.effect(hack, true);
			assertTrue(full.ticks() > 0, hack.wire());
			assertEquals(Math.max(PvpHackRules.MIN_TICKS, full.ticks() / 2), resisted.ticks(), hack.wire());
			assertTrue(resisted.ticks() < full.ticks(), hack.wire());
			assertEquals(full.damage(), resisted.damage(), hack.wire());
			assertEquals(full.amplifier(), resisted.amplifier(), hack.wire());
		}

		assertEquals(new PvpHackRules.Effect(30, 4.0F, 6), PvpHackRules.effect(Hack.SHORT_CIRCUIT, false));
		assertEquals(100, PvpHackRules.effect(Hack.REBOOT_OPTICS, false).ticks());
		assertEquals(50, PvpHackRules.effect(Hack.REBOOT_OPTICS, true).ticks());
		assertTrue(PvpHackRules.effect(Hack.OVERHEAT, false).damage() > 0.0F);
		assertEquals(0.0F, PvpHackRules.effect(Hack.PING, false).damage());
	}

	/** The glitch DOWN carries whole seconds 0..10 (megaplan section 1.9). */
	@Test
	void glitchSeconds() {
		assertEquals(5, PvpHackRules.glitchSeconds(100));
		assertEquals(3, PvpHackRules.glitchSeconds(50));
		assertEquals(1, PvpHackRules.glitchSeconds(1));
		assertEquals(0, PvpHackRules.glitchSeconds(0));
		assertEquals(10, PvpHackRules.glitchSeconds(999));
	}

	/** Counterplay: a charged Firewall stops the hack before the upload; a solved Breach stops it; anything else lands. */
	@Test
	void counterplay() {
		assertEquals(PvpHackRules.Result.FIREWALL, PvpHackRules.result(true, true));
		assertEquals(PvpHackRules.Result.FIREWALL, PvpHackRules.result(true, false));
		assertEquals(PvpHackRules.Result.BREACH, PvpHackRules.result(false, true));
		assertEquals(PvpHackRules.Result.LANDED, PvpHackRules.result(false, false));
	}

	/** The target gets a few seconds to start the short Breach (6-B1: 4x4, 5 s), then its timer; without a Breach a short upload. */
	@Test
	void uploadWindow() {
		assertEquals(4, BreachContent.SHORT.grid());
		assertEquals(5, BreachContent.SHORT.seconds());
		assertEquals(PvpHackRules.START_TICKS + 5 * 20 + PvpHackRules.GRACE_TICKS, PvpHackRules.uploadTicks(true));
		assertEquals(PvpHackRules.UPLOAD_TICKS, PvpHackRules.uploadTicks(false));
		assertTrue(PvpHackRules.uploadTicks(true) <= 10 * 20);
		assertTrue(PvpHackRules.START_TICKS >= 40 && PvpHackRules.START_TICKS <= 80);
	}
}
