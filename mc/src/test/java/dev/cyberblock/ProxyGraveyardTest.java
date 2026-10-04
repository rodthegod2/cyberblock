package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.ProxyDiff.Entry;
import dev.cyberblock.ProxyDiff.Graveyard;
import dev.cyberblock.ProxyDiff.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 2026-10-04 task 3-F1: bodies and wrecks. */
class ProxyGraveyardTest {
	private static final long S = 1_000_000_000L;

	private static Entry at(final long id, final double x) {
		return new Entry(id, x, 64, 0, 90, Kind.HUMAN, true);
	}

	@Test
	void aBodyLiesThreeSecondsAWreckFive() {
		assertEquals(60, ProxyDiff.deadTicks(Kind.HUMAN));
		assertEquals(100, ProxyDiff.deadTicks(Kind.VEHICLE));
	}

	@Test
	void aKillAfterTheListLeftItOutStillLeavesTheBodyWhereItWas() {
		Graveyard g = new Graveyard();
		g.left(at(7, 12.5), 10 * S);
		Entry body = g.killed(7, 11 * S);
		assertNotNull(body);
		assertEquals(12.5, body.x());
		assertNull(g.killed(7, 11 * S), "one body per death");
	}

	@Test
	void aKillLongAfterItLeftLeavesNoBody() {
		Graveyard g = new Graveyard();
		g.left(at(7, 1), 10 * S);
		assertNull(g.killed(7, 10 * S + Graveyard.GONE_NANOS + 1));
		assertNull(new Graveyard().killed(8, S), "never seen: nothing to lay down");
	}

	@Test
	void aDeadIdIsNotStoodUpAgainWhileTheHostStillListsIt() {
		Graveyard g = new Graveyard();
		g.killed(7, 10 * S);
		List<Entry> listed = List.of(at(7, 1), at(8, 2));
		assertEquals(List.of(at(8, 2)), g.alive(listed, 11 * S));
		assertEquals(listed, g.alive(listed, 10 * S + Graveyard.DEAD_NANOS + 1), "a recycled id lives again later");
	}

	@Test
	void whatRunsOutIsForgotten() {
		Graveyard g = new Graveyard();
		g.left(at(7, 1), 10 * S);
		g.alive(List.of(), 10 * S + Graveyard.GONE_NANOS + 1);
		assertNull(g.killed(7, 10 * S + Graveyard.GONE_NANOS + 1));
		List<Entry> none = List.of();
		assertSame(none, new Graveyard().alive(none, S), "nothing dead: the list as it came");
	}
}
