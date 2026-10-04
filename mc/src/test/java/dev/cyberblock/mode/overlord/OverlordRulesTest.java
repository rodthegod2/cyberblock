package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Review fixes of 8O: the void has no ground, the console poll is rate limited, the hooks fail closed, supply drops pay capped eddies. */
class OverlordRulesTest {
	@Test
	void anEmptyColumnOfTheVoidWorldHasNoGround() {
		// the heightmap of a column without blocks is the world's bottom: no spot there
		assertFalse(OverlordWorld.hasGround(-64, -64));
		assertTrue(OverlordWorld.hasGround(-63, -64), "one block at the bottom is ground");
		assertTrue(OverlordWorld.hasGround(80, -64));
	}

	@Test
	void theConsolePollIsRateLimitedPerPlayer() {
		Map<UUID, Long> last = new HashMap<>();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		assertTrue(Overlord.due(last, a, 1_000L, Overlord.POLL_MS));
		assertFalse(Overlord.due(last, a, 1_100L, Overlord.POLL_MS), "a flood of ovlpoll gets one answer per 250 ms");
		assertTrue(Overlord.due(last, b, 1_100L, Overlord.POLL_MS), "per player");
		assertTrue(Overlord.due(last, a, 1_250L, Overlord.POLL_MS));
		assertFalse(Overlord.due(last, a, 4_000L, Overlord.NOT_OVERLORD_POLL_MS), "a non-Overlord is answered once every 5 s");
		assertTrue(Overlord.NOT_OVERLORD_POLL_MS > Overlord.POLL_MS);
	}

	@Test
	void unsetHooksFailClosed() {
		// until 8M's hook is wired no match is running, so a lobby or an ended match never gets Overlord spawns or events
		assertSame(OverlordHooks.NOT_SET, OverlordHooks.running);
		assertFalse(OverlordHooks.running.test("lobby-match"));
		assertNull(OverlordHooks.area.apply("any"));
		assertEquals(0, OverlordHooks.heat.getAsInt());
	}

	@Test
	void supplyDropEddiesAreSplitAndCappedPerDropMatchAndPlayer() {
		assertArrayEquals(new int[0], Fairness.eddieShares(100, 0, new int[0]), "nobody opted in near it: nobody is paid");
		assertArrayEquals(new int[] {50, 50}, Fairness.eddieShares(100, 0, new int[] {0, 0}));
		assertArrayEquals(new int[] {100}, Fairness.eddieShares(2000, 0, new int[] {0}), "at most DROP_EDDIES per drop");
		assertArrayEquals(new int[] {40, 40}, Fairness.eddieShares(100, Fairness.MATCH_EDDIES - 80, new int[] {0, 0}), "the match cap");
		assertArrayEquals(new int[] {10, 50}, Fairness.eddieShares(100, 0, new int[] {Fairness.PLAYER_EDDIES - 10, 0}), "the player cap");
		assertArrayEquals(new int[] {0}, Fairness.eddieShares(100, Fairness.MATCH_EDDIES, new int[] {0}), "a spent match pays nothing more");
	}
}
