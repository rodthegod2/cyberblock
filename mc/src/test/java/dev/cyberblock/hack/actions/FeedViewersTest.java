package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The photo feed is requested only while someone watches (megaplan 6-D1: `feedreq on/off`), and the cooldown clock. */
class FeedViewersTest {
	private static final UUID HOST = new UUID(1, 1), OTHER = new UUID(1, 2), ANA = new UUID(2, 1), BEN = new UUID(2, 2);

	@Test
	void onWithTheFirstViewerOffAfterTheLast() {
		FeedViewers v = new FeedViewers();
		assertTrue(v.changes(0L).isEmpty());
		v.watch(HOST, ANA, true, 0L);
		v.watch(HOST, BEN, true, 0L);
		assertEquals(List.of(new FeedViewers.Change(HOST, true)), v.changes(0L));
		assertTrue(v.changes(1000L).isEmpty(), "asked once");
		assertEquals(Set.of(ANA, BEN), v.screenViewers(HOST, 1000L));
		v.watch(HOST, ANA, false, 1000L);
		assertEquals(Set.of(BEN), v.screenViewers(HOST, 1000L));
		assertTrue(v.changes(2000L).isEmpty());
		// Ben never renewed: his watch runs out after 5 s
		assertEquals(List.of(new FeedViewers.Change(HOST, false)), v.changes(FeedViewers.WATCH_MS + 1L));
		assertTrue(v.changes(FeedViewers.WATCH_MS + 2L).isEmpty());
	}

	@Test
	void mapHoldersCountButGetNoFrames() {
		FeedViewers v = new FeedViewers();
		v.holding(OTHER, ANA, 0L);
		assertEquals(List.of(new FeedViewers.Change(OTHER, true)), v.changes(0L));
		assertTrue(v.screenViewers(OTHER, 0L).isEmpty(), "the map item shows the frame, not the Net Map");
		v.restarted(OTHER);
		assertEquals(List.of(new FeedViewers.Change(OTHER, true)), v.changes(10L), "a new hello forgot the request");
		v.left(ANA);
		assertEquals(List.of(new FeedViewers.Change(OTHER, false)), v.changes(20L));
		v.watch(HOST, BEN, true, 30L);
		v.changes(30L);
		v.ended(HOST);
		assertTrue(v.changes(40L).isEmpty(), "an ended host is told nothing");
	}

	@Test
	void cooldownsRoundUp() {
		Cooldowns c = new Cooldowns();
		assertEquals(0L, c.secondsLeft("a", 0L));
		c.start("a", 1000L, 2L);
		assertEquals(2L, c.secondsLeft("a", 1000L));
		assertEquals(1L, c.secondsLeft("a", 2001L));
		assertEquals(0L, c.secondsLeft("a", 3000L));
		c.start("b", 0L, 0L);
		assertEquals(0L, c.secondsLeft("b", 0L));
		c.start("c", 0L, 300L);
		c.cancel("c");
		assertEquals(0L, c.secondsLeft("c", 1L), "cancelled at once");
	}

	@Test
	void ratesCountTheLastSecond() {
		Cooldowns c = new Cooldowns();
		for (int i = 0; i < 4; i++) {
			assertTrue(c.withinRate("rhack:ana", 100L * i, 4), "request " + i);
		}

		assertFalse(c.withinRate("rhack:ana", 500L, 4), "a fifth within the second");
		assertTrue(c.withinRate("rhack:ben", 500L, 4), "per key");
		assertTrue(c.withinRate("rhack:ana", 1000L, 4), "the first one left the window");
	}

	@Test
	void hostsNobodyWatchesLeaveNothingBehind() {
		// a client sending watches for random hosts must not grow the server: stops add nothing, expired entries go
		FeedViewers v = new FeedViewers();
		for (int i = 0; i < 1000; i++) {
			v.watch(UUID.randomUUID(), ANA, false, 0L);
		}

		assertEquals(0, v.hostsKept());
		v.watch(HOST, ANA, true, 0L);
		v.holding(OTHER, BEN, 0L);
		v.changes(0L);
		assertEquals(2, v.hostsKept());
		v.watch(HOST, ANA, false, 10L);
		v.changes(FeedViewers.WATCH_MS + 1L);
		assertEquals(0, v.hostsKept(), "the held map ran out and the watch stopped: both hosts are off and forgotten");
		v.watch(HOST, ANA, true, 0L);
		v.left(ANA);
		v.changes(1L);
		v.changes(2L);
		assertEquals(0, v.hostsKept());
	}
}
