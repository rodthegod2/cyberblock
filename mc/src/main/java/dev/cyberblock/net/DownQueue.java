package dev.cyberblock.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One session's DOWN queue (megaplan section 1.3): bounded at 2048 messages, flushed every server tick in order, at most PER_TICK a tick
 * (megaplan 9-F1: a burst spreads over the next ticks). The newest
 * {@code mobs} and {@code proj} replace a queued one of the same type (in its place); past the bound the oldest droppable message
 * ({@code block}, {@code blocks}, {@code mobs}, {@code proj}; else the oldest of all) goes and a blocks resync is owed. Server
 * thread only.
 */
final class DownQueue {
	static final int BOUND = 2048;
	/** Most messages one session sends in a server tick (megaplan 9-F1); the rest wait for the next tick, in order. */
	static final int PER_TICK = 128;
	private static final Set<String> REPLACED = Set.of("mobs", "proj");
	private static final Set<String> DROPPABLE = Set.of("block", "blocks", "mobs", "proj");
	private final List<Item> items = new ArrayList<>();
	private boolean resyncDue;
	private long dropped;

	private record Item(String t, String json) {
	}

	/** The message's "t" (the first "t" key; our builders put it first), "" when there is none. */
	static String typeOf(final String json) {
		int i = json.indexOf("\"t\":\"");
		if (i < 0) {
			return "";
		}

		int end = json.indexOf('"', i + 5);
		return end < 0 ? "" : json.substring(i + 5, end);
	}

	void offer(final String json) {
		String t = typeOf(json);
		if (REPLACED.contains(t)) {
			for (int i = 0; i < this.items.size(); i++) {
				if (this.items.get(i).t.equals(t)) {
					this.items.set(i, new Item(t, json));
					return;
				}
			}
		}

		if (this.items.size() >= BOUND) {
			this.dropOldest();
		}

		this.items.add(new Item(t, json));
	}

	private void dropOldest() {
		int victim = 0;
		for (int i = 0; i < this.items.size(); i++) {
			if (DROPPABLE.contains(this.items.get(i).t)) {
				victim = i;
				break;
			}
		}

		this.items.remove(victim);
		this.dropped++;
		this.resyncDue = true;
	}

	/** Every queued message in order, then the queue is empty. */
	void drain(final Consumer<String> out) {
		this.drain(out, Integer.MAX_VALUE);
	}

	/**
	 * At most `max` queued messages, oldest first; the rest stay queued for the next call (still replaced and bounded). Returns how many
	 * stayed.
	 */
	int drain(final Consumer<String> out, final int max) {
		int n = Math.min(Math.max(max, 0), this.items.size());
		List<Item> head = this.items.subList(0, n);
		List<Item> batch = List.copyOf(head);
		head.clear();
		for (Item it : batch) {
			out.accept(it.json);
		}

		return this.items.size();
	}

	int size() {
		return this.items.size();
	}

	void clear() {
		this.items.clear();
	}

	long dropped() {
		return this.dropped;
	}

	/** A blocks resync is owed because the bound dropped something; reading it clears it. */
	boolean takeResync() {
		boolean due = this.resyncDue;
		this.resyncDue = false;
		return due;
	}
}
