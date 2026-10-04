package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
	@Test
	void startsFullRefillsAtTheRateAndNeverAboveTheBurst() {
		AtomicLong now = new AtomicLong(0);
		TokenBucket b = new TokenBucket(10, 20, now::get);
		for (int i = 0; i < 20; i++) {
			assertTrue(b.tryTake(1));
		}

		assertFalse(b.tryTake(1));
		now.addAndGet(100);
		assertTrue(b.tryTake(1), "10/s: one token per 100 ms");
		assertFalse(b.tryTake(1));
		now.addAndGet(60_000);
		int n = 0;
		while (b.tryTake(1)) {
			n++;
		}

		assertEquals(20, n, "capped at the burst");
	}

	@Test
	void aBigTakeIsAllOrNothing() {
		AtomicLong now = new AtomicLong(0);
		TokenBucket b = new TokenBucket(1000, 2000, now::get);
		assertTrue(b.tryTake(1500));
		assertFalse(b.tryTake(600));
		assertTrue(b.tryTake(500));
	}

	@Test
	void aClockGoingBackDoesNotMintTokens() {
		AtomicLong now = new AtomicLong(10_000);
		TokenBucket b = new TokenBucket(10, 1, now::get);
		assertTrue(b.tryTake(1));
		now.set(0);
		assertFalse(b.tryTake(1));
		now.set(10_050);
		assertFalse(b.tryTake(1), "only 50 ms after the last real time");
		now.set(10_100);
		assertTrue(b.tryTake(1));
	}
}
