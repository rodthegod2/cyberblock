package dev.cyberblock.net;

import java.util.function.LongSupplier;

/** A token bucket: refills at rate tokens per second up to capacity (the burst); starts full. The clock is in milliseconds. */
public final class TokenBucket {
	private final double rate;
	private final double capacity;
	private final LongSupplier clock;
	private double tokens;
	private long last;

	public TokenBucket(final double rate, final double capacity, final LongSupplier clock) {
		this.rate = rate;
		this.capacity = capacity;
		this.clock = clock;
		this.tokens = capacity;
		this.last = clock.getAsLong();
	}

	/** Takes n tokens if there are that many; false (and nothing taken) otherwise. */
	public boolean tryTake(final double n) {
		long now = this.clock.getAsLong();
		if (now > this.last) {
			this.tokens = Math.min(this.capacity, this.tokens + (now - this.last) / 1000.0 * this.rate);
		}

		this.last = Math.max(this.last, now);
		if (this.tokens >= n) {
			this.tokens -= n;
			return true;
		}

		return false;
	}

	double capacity() {
		return this.capacity;
	}
}
