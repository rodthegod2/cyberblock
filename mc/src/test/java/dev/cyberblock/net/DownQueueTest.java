package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownQueueTest {
	private static List<String> drain(final DownQueue q) {
		List<String> out = new ArrayList<>();
		q.drain(out::add);
		return out;
	}

	/** Megaplan 9-F1: at most the budget a tick, the rest in order on the next ticks, still replaced while it waits. */
	@Test
	void aBudgetedDrainLeavesTheRestInOrder() {
		DownQueue q = new DownQueue();
		for (int i = 0; i < 5; i++) {
			q.offer("{\"t\":\"hit\",\"n\":" + i + "}");
		}

		q.offer("{\"t\":\"mobs\",\"n\":1}");
		List<String> out = new ArrayList<>();
		assertEquals(3, q.drain(out::add, 3));
		assertEquals(List.of("{\"t\":\"hit\",\"n\":0}", "{\"t\":\"hit\",\"n\":1}", "{\"t\":\"hit\",\"n\":2}"), out);
		q.offer("{\"t\":\"mobs\",\"n\":2}"); // replaces the waiting one in its place
		assertEquals(List.of("{\"t\":\"hit\",\"n\":3}", "{\"t\":\"hit\",\"n\":4}", "{\"t\":\"mobs\",\"n\":2}"), drain(q));
		assertEquals(0, q.drain(out::add, DownQueue.PER_TICK));
		assertTrue(DownQueue.PER_TICK >= 64, "a normal tick never waits");
	}

	@Test
	void typeOfReadsTheFirstT() {
		assertEquals("align", DownQueue.typeOf("{\"t\":\"align\",\"k\":1}"));
		assertEquals("", DownQueue.typeOf("{\"k\":1}"));
	}

	@Test
	void theNewestMobsAndProjReplaceTheQueuedOneInPlace() {
		DownQueue q = new DownQueue();
		q.offer("{\"t\":\"mobs\",\"n\":1}");
		q.offer("{\"t\":\"block\",\"n\":2}");
		q.offer("{\"t\":\"proj\",\"n\":3}");
		q.offer("{\"t\":\"mobs\",\"n\":4}");
		q.offer("{\"t\":\"proj\",\"n\":5}");
		q.offer("{\"t\":\"hit\",\"n\":6}");
		assertEquals(List.of("{\"t\":\"mobs\",\"n\":4}", "{\"t\":\"block\",\"n\":2}", "{\"t\":\"proj\",\"n\":5}", "{\"t\":\"hit\",\"n\":6}"), drain(q));
		assertEquals(0, q.size());
	}

	@Test
	void pastTheBoundTheOldestDroppableGoesAndAResyncIsOwed() {
		DownQueue q = new DownQueue();
		q.offer("{\"t\":\"align\",\"k\":1}");
		for (int i = 0; i < DownQueue.BOUND - 1; i++) {
			q.offer("{\"t\":\"block\",\"i\":" + i + "}");
		}

		assertFalse(q.takeResync());
		q.offer("{\"t\":\"effect\",\"x\":1}");
		assertEquals(DownQueue.BOUND, q.size());
		assertTrue(q.takeResync());
		assertFalse(q.takeResync(), "reading clears it");
		List<String> out = drain(q);
		assertEquals("{\"t\":\"align\",\"k\":1}", out.get(0), "align is not droppable");
		assertEquals("{\"t\":\"block\",\"i\":1}", out.get(1), "the oldest block went");
		assertEquals("{\"t\":\"effect\",\"x\":1}", out.get(out.size() - 1));
		assertEquals(1, q.dropped());
	}

	@Test
	void withNothingDroppableTheOldestGoes() {
		DownQueue q = new DownQueue();
		for (int i = 0; i <= DownQueue.BOUND; i++) {
			q.offer("{\"t\":\"effect\",\"i\":" + i + "}");
		}

		List<String> out = drain(q);
		assertEquals(DownQueue.BOUND, out.size());
		assertEquals("{\"t\":\"effect\",\"i\":1}", out.get(0));
	}
}
