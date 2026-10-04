package dev.cyberblock.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.net.Roster;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Megaplan 5-K1: the player list marker and the ally outline rule (allies = none until Phase 8 sets teams). */
class IndicatorsTest {
	private static Roster.Entry entry(final UUID uuid, final int team, final boolean hidden) {
		return new Roster.Entry(uuid, "p", true, "male", team, false, false, hidden);
	}

	@Test
	void linkedPlayersGetTheMarker() {
		Component name = Component.literal("CbHost1");
		assertEquals("CbHost1 ◆", TabMarker.decorate(name, true).getString());
		assertSame(name, TabMarker.decorate(name, false), "a guest's name is left as it is");
	}

	@Test
	void noAlliesWithoutTeams() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		assertFalse(AllyOutlines.allied(entry(a, -1, false), entry(b, -1, false)), "Phase 5: every roster team is -1");
		assertFalse(AllyOutlines.allied(entry(a, -1, false), null));
		assertFalse(AllyOutlines.allied(null, entry(b, 1, false)));
	}

	@Test
	void sameTeamIsAnAllyButNeverYourself() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		assertTrue(AllyOutlines.allied(entry(a, 2, false), entry(b, 2, false)));
		assertFalse(AllyOutlines.allied(entry(a, 2, false), entry(b, 3, false)));
		assertFalse(AllyOutlines.allied(entry(a, 2, false), entry(a, 2, false)));
		assertFalse(AllyOutlines.allied(entry(a, 2, false), entry(b, 2, true)), "a hidden player shows no outline");
	}

	@Test
	void teamColoursAreOpaqueEnoughAndRepeat() {
		assertEquals(AllyOutlines.colour(0), AllyOutlines.colour(8));
		assertNotEquals(AllyOutlines.colour(0), AllyOutlines.colour(1));
		assertTrue((AllyOutlines.colour(3) >>> 24) >= 0xC0);
	}
}
