package dev.cyberblock.budget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Work that hosts' messages bring, done at most {@code budget} units a server tick (megaplan task 9-F1). Each owner's jobs run in the
 * order they came; a job runs at once when its owner has nothing waiting and the tick has budget left, else it waits for the next
 * tick, where the waiting jobs run owner by owner in turn (round robin) while the budget lasts. A job is never split: one bigger than
 * what is left still runs whole, and the overdraft is taken from the next ticks, so the work averages out at the budget. Past
 * {@code maxQueued} waiting jobs an owner's job runs at once (the budget never makes a queue grow without end). Pure: no game in here;
 * server thread only.
 */
public final class TickQueue {
	/** One job: does its work and says how many units it used. */
	@FunctionalInterface
	public interface Job {
		int run();
	}

	private record Waiting(int estimate, Job job) {
	}

	private final int budget, maxQueued;
	private final Consumer<RuntimeException> failed;
	private final Map<UUID, ArrayDeque<Waiting>> waiting = new LinkedHashMap<>();
	private int left;
	/** Since the last report: jobs and units that had to wait, the longest an owner's queue got, jobs run past a full queue. */
	private long deferredJobs, deferredUnits, overflowed;
	private int longest;

	/** @param failed told about a job that threw (the rest still run) */
	public TickQueue(final int budget, final int maxQueued, final Consumer<RuntimeException> failed) {
		this.budget = budget;
		this.maxQueued = maxQueued;
		this.failed = failed;
		this.left = budget;
	}

	public int budget() {
		return this.budget;
	}

	/** A new job of `estimate` units for this owner: run now, or queued behind the owner's waiting jobs. */
	public void offer(final UUID owner, final int estimate, final Job job) {
		ArrayDeque<Waiting> q = this.waiting.get(owner);
		boolean queueFull = q != null && q.size() >= this.maxQueued;
		if ((q == null || q.isEmpty()) && this.left > 0 || queueFull) {
			this.overflowed += queueFull ? 1 : 0;
			this.left -= this.run(job);
			return;
		}

		if (q == null) {
			q = new ArrayDeque<>();
			this.waiting.put(owner, q);
		}

		q.add(new Waiting(estimate, job));
		this.deferredJobs++;
		this.deferredUnits += Math.max(estimate, 0);
		this.longest = Math.max(this.longest, q.size());
	}

	/** The start of a server tick: the budget comes back (less any overdraft), then the waiting jobs run in turn while it lasts. */
	public void tick() {
		this.left = Math.min(this.budget, this.left + this.budget);
		List<UUID> owners = new ArrayList<>(this.waiting.keySet());
		boolean ran = true;
		while (this.left > 0 && ran) {
			ran = false;
			for (UUID owner : owners) {
				ArrayDeque<Waiting> q = this.waiting.get(owner);
				if (q != null && !q.isEmpty() && this.left > 0) {
					this.left -= this.run(q.poll().job());
					ran = true;
				}
			}
		}

		for (Iterator<ArrayDeque<Waiting>> it = this.waiting.values().iterator(); it.hasNext();) {
			if (it.next().isEmpty()) {
				it.remove();
			}
		}
	}

	private int run(final Job job) {
		try {
			return Math.max(job.run(), 0);
		} catch (RuntimeException e) {
			this.failed.accept(e);
			return 1;
		}
	}

	/** The owner's session ended: its waiting jobs are dropped (they belong to it). */
	public void drop(final UUID owner) {
		this.waiting.remove(owner);
	}

	/** Server stop: nothing waits, the budget is full. */
	public void clear() {
		this.waiting.clear();
		this.left = this.budget;
	}

	/** Jobs waiting now, over every owner. */
	public int queued() {
		int n = 0;
		for (ArrayDeque<Waiting> q : this.waiting.values()) {
			n += q.size();
		}

		return n;
	}

	/** Since the last call: {jobs that waited, their units, the longest queue, jobs run at once past a full queue}; then it starts over. */
	public long[] takeStats() {
		long[] s = {this.deferredJobs, this.deferredUnits, this.longest, this.overflowed};
		this.deferredJobs = this.deferredUnits = this.overflowed = 0L;
		this.longest = this.queued() > 0 ? this.longestNow() : 0;
		return s;
	}

	private int longestNow() {
		int n = 0;
		for (ArrayDeque<Waiting> q : this.waiting.values()) {
			n = Math.max(n, q.size());
		}

		return n;
	}
}
