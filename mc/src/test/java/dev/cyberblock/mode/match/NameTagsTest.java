package dev.cyberblock.mode.match;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 8-M1: name tags never put strangers on one vanilla team (vanilla treats a team as allies). */
class NameTagsTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a"), B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	@Test
	void pvpOutsideAMatchIsATeamOfOne() {
		assertNull(NameTags.teamName(null, -1, false, false, false, A), "nothing to show");
		String a = NameTags.teamName(null, -1, false, true, false, A), b = NameTags.teamName(null, -1, false, true, false, B);
		assertNotEquals(a, b, "two strangers with PvP on are not allies");
		assertTrue(a.startsWith(NameTags.PREFIX));
		assertNotEquals(a, NameTags.teamName(null, -1, false, true, true, A), "the [V] look is its own team");
	}

	@Test
	void aMatchTeamIsOneVanillaTeam() {
		String linked = NameTags.teamName("m1", 0, true, true, true, A), mcOnly = NameTags.teamName("m1", 0, true, false, false, B);
		assertEquals(linked, mcOnly, "linked or not, PvP toggle or not: teammates share one team");
		assertEquals("cbm_m1_0p", linked);
		assertEquals("cbm_m1_0", NameTags.teamName("m1", 0, false, true, false, A), "the lobby, or a match without PvP");
		assertNotEquals(linked, NameTags.teamName("m1", 1, true, true, true, B), "the other team");
	}
}
