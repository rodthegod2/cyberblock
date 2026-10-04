package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 8-O1 and contract 1.6: what reaches a host's own game from the Overlord, and which host a Night City spawn goes to. */
class HostRulesTest {
	private static final UUID HOST = UUID.randomUUID(), OVERLORD = UUID.randomUUID();

	@Test
	void aHostWithSpawnsOffGetsNoHackGlitchOrExplosion() {
		// opted in, every other toggle on, spawns off (its default): nothing from the Overlord or the Auto-Director (by null)
		assertFalse(HostRules.consents(true, false, false), "glitch (EMP)");
		assertFalse(HostRules.rhack(true, false, false, true), "rhack blackout or gridlock");
		assertFalse(HostRules.explosion(true, false, false, true), "airstrike or Overlord mob blast");
	}

	@Test
	void aHostNotOptedInToTheMatchGetsNothingWhateverItsToggles() {
		assertFalse(HostRules.consents(false, false, true));
		assertFalse(HostRules.rhack(false, false, true, true));
		assertFalse(HostRules.explosion(false, false, true, true));
	}

	@Test
	void anOptedInHostWithSpawnsOnGetsThemWithinItsOtherToggles() {
		assertTrue(HostRules.consents(true, false, true));
		assertTrue(HostRules.rhack(true, false, true, true));
		assertFalse(HostRules.rhack(true, false, true, false), "the hacks toggle still counts");
		assertTrue(HostRules.explosion(true, false, true, true));
		assertFalse(HostRules.explosion(true, false, true, false), "the explosions toggle still counts");
		assertTrue(HostRules.explosion(true, true, false, false), "a host's own action needs no toggle");
	}

	private static Fairness.Who who(final String name, final double x, final boolean eligible) {
		return new Fairness.Who(name, x, 0, eligible);
	}

	@Test
	void aHostNotOptedInIsNeverPickedEvenWithinReach() {
		HostRules.Host notOpted = new HostRules.Host(HOST, 40, 0, false);
		assertNull(HostRules.spawnHost(List.of(notOpted), List.of(who("host", 40, false)), 0, 0, 64));
	}

	@Test
	void aSpotAimedAtAMinecraftOnlyGuestIsAMinecraftSquadEvenWithAHost50BlocksAway() {
		HostRules.Host host = new HostRules.Host(HOST, 50, 0, true);
		List<Fairness.Who> players = List.of(who("guest", -10, true), who("host", 50, true));
		assertNull(HostRules.spawnHost(List.of(host), players, 0, 0, 64), "the guest is nearer: its enemies must be able to fight it");
	}

	@Test
	void theHostTheSpotIsAimedAtIsPicked() {
		HostRules.Host near = new HostRules.Host(HOST, 20, 0, true), far = new HostRules.Host(OVERLORD, 60, 0, true);
		// within NEAR: picked even with a guest a little nearer
		assertEquals(near, HostRules.spawnHost(List.of(far, near), List.of(who("guest", 5, true), who("host", 20, true)), 0, 0, 64));
		// 40 blocks away and the nearest opted-in player: picked
		HostRules.Host host40 = new HostRules.Host(HOST, 40, 0, true);
		assertEquals(host40, HostRules.spawnHost(List.of(host40), List.of(who("host", 40, true), who("guest", -45, true)), 0, 0, 64));
		// beyond the radius: none
		assertNull(HostRules.spawnHost(List.of(new HostRules.Host(HOST, 70, 0, true)), List.of(who("host", 70, true)), 0, 0, 64));
	}
}
