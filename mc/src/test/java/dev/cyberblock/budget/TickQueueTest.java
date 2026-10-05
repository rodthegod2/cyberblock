package dev.cyberblock.budget;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: work at most a budget a tick, each host's in order, the hosts in turn, an overdraft paid back. */
class TickQueueTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
	private final List<String> ran = new ArrayList<>();

	private TickQueue.Job job(final String name, final int units) {
		return () -> {
			this.ran.add(name);
			return units;
		};
	}

	@Test
	void runsAtOnceWhileTheBudgetLastsThenWaitsInOrder() {
		TickQueue q = new TickQueue(100, 50, e -> {});
		q.offer(A, 60, this.job("a1", 60));
		q.offer(A, 60, this.job("a2", 60)); // 40 left: still runs (a job is never split), the budget is overdrawn
		q.offer(A, 10, this.job("a3", 10)); // nothing left: waits
		q.offer(B, 10, this.job("b1", 10)); // B has nothing waiting, but the tick's budget is spent
		assertEquals(List.of("a1", "a2"), this.ran);
		assertEquals(2, q.queued());
		q.tick(); // 100 back less the 20 overdrawn
		assertEquals(List.of("a1", "a2", "a3", "b1"), this.ran);
		assertEquals(0, q.queued());
	}

	@Test
	void anOwnersLaterJobNeverOvertakesItsWaitingOne() {
		TickQueue q = new TickQueue(10, 50, e -> {});
		q.offer(A, 30, this.job("big", 30)); // overdraws by 20
		q.offer(A, 1, this.job("ground", 1));
		q.tick(); // -20 + 10 = -10: nothing runs
		q.offer(A, 1, this.job("clear", 1)); // A has one waiting: queued behind it
		assertEquals(List.of("big"), this.ran);
		q.tick(); // 0: still nothing
		q.tick(); // 10
		assertEquals(List.of("big", "ground", "clear"), this.ran);
	}

	@Test
	void ownersTakeTurns() {
		TickQueue q = new TickQueue(10, 50, e -> {});
		q.offer(A, 10, this.job("a0", 10));
		for (int i = 1; i <= 3; i++) {
			q.offer(A, 5, this.job("a" + i, 5));
			q.offer(B, 5, this.job("b" + i, 5));
		}

		q.tick();
		assertEquals(List.of("a0", "a1", "b1"), this.ran);
		q.tick();
		assertEquals(List.of("a0", "a1", "b1", "a2", "b2"), this.ran);
	}

	@Test
	void aFullQueueRunsItsOldestJobToMakeRoomAndKeepsTheOrder() {
		TickQueue q = new TickQueue(1, 2, e -> {});
		q.offer(A, 1, this.job("a0", 1));
		q.offer(A, 1, this.job("a1", 1));
		q.offer(A, 1, this.job("a2", 1));
		q.offer(A, 1, this.job("a3", 1)); // two waiting already: a1 runs now, a3 waits behind a2
		assertEquals(List.of("a0", "a1"), this.ran);
		assertEquals(2, q.queued());
		assertArrayEquals(new long[] {3, 3, 2, 1}, q.takeStats());
		q.tick(); // -1 + 1 = 0: nothing
		q.tick();
		q.tick();
		assertEquals(List.of("a0", "a1", "a2", "a3"), this.ran);
	}

	@Test
	void anEndedOwnersWaitingWorkIsDropped() {
		TickQueue q = new TickQueue(1, 50, e -> {});
		q.offer(A, 1, this.job("a0", 1));
		q.offer(A, 1, this.job("a1", 1));
		q.drop(A);
		q.tick();
		assertEquals(List.of("a0"), this.ran, "the ended session's work is gone");
		assertEquals(0, q.queued());
	}

	@Test
	void theOverdraftHasAFloor() {
		TickQueue q = new TickQueue(10, 50, e -> {});
		q.offer(A, 1000, this.job("huge", 1000)); // would be 99 ticks of debt; stops at MAX_OVERDRAFT_TICKS
		q.offer(B, 1, this.job("b", 1));
		for (int i = 0; i < TickQueue.MAX_OVERDRAFT_TICKS; i++) {
			q.tick();
		}

		assertEquals(List.of("huge"), this.ran);
		q.tick();
		assertEquals(List.of("huge", "b"), this.ran);
	}

	@Test
	void aJobThatThrowsIsReportedAndTheRestRun() {
		List<RuntimeException> failures = new ArrayList<>();
		TickQueue q = new TickQueue(1, 50, failures::add);
		q.offer(A, 1, this.job("first", 1));
		q.offer(A, 1, () -> {
			throw new IllegalStateException("bad");
		});
		q.offer(A, 1, this.job("after", 1));
		q.tick();
		q.tick();
		assertEquals(List.of("first", "after"), this.ran);
		assertEquals(1, failures.size());
		assertTrue(failures.get(0).getMessage().contains("bad"));
	}

	@Test
	void clearForgetsEverything() {
		TickQueue q = new TickQueue(1, 50, e -> {});
		q.offer(A, 5, this.job("a", 5));
		q.offer(A, 1, this.job("waits", 1));
		q.clear();
		q.offer(B, 1, this.job("b", 1)); // the budget is full again
		assertEquals(List.of("a", "b"), this.ran);
		assertEquals(0, q.queued());
	}
}
