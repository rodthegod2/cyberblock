package dev.cyberblock.hub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: the leaderboard boards' order and titles. */
class LeaderboardsTest {
	@Test
	void bestFirstThenByName() {
		List<Leaderboards.Row> rows = List.of(new Leaderboards.Row("zed", 3), new Leaderboards.Row("Amy", 5), new Leaderboards.Row("bob", 3),
			new Leaderboards.Row("Cat", 9));
		assertEquals(List.of(new Leaderboards.Row("Cat", 9), new Leaderboards.Row("Amy", 5), new Leaderboards.Row("bob", 3), new Leaderboards.Row("zed", 3)),
			Leaderboards.rank(rows, 10));
		assertEquals(2, Leaderboards.rank(rows, 2).size());
		assertEquals(List.of(), Leaderboards.rank(List.of(), Leaderboards.SHOWN));
	}

	@Test
	void theObjectivesFitTheScoreboard() {
		for (String o : List.of(Leaderboards.MATCH_WINS, Leaderboards.GIGS_DONE, Leaderboards.CHAOS)) {
			assertTrue(o.matches("[a-z0-9_]{1,16}"), o);
		}
	}

	@Test
	void aTitleIsItsLangKeyWithEnglishBehind() {
		TranslatableContents t = (TranslatableContents) Leaderboards.title(Leaderboards.GIGS_DONE, "cyberblock.afterlife.board.gigs").getContents();
		assertEquals("cyberblock.afterlife.board.gigs", t.getKey());
		assertEquals("Gigs Done", t.getFallback());
		assertEquals("Chaos", Leaderboards.title(Leaderboards.CHAOS, "").getString(), "no key: the English");
		assertEquals("cb_custom", Leaderboards.title("cb_custom", "").getString(), "a datapack's objective shows its name");
	}
}
