package dev.cyberblock;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which hosts' Night City ground each barrier cell is (plan 2026-10-04 section 1.4: barriers per session). Two hosts can stand in
 * the same street: a barrier goes only when the last host that reported it lets go, and a host's end takes out only the cells no
 * other host still reports. No Minecraft world in here: WorldBridge places and removes the blocks. Server thread only.
 */
final class Claims {
	private final Long2ObjectOpenHashMap<Set<UUID>> owners = new Long2ObjectOpenHashMap<>();
	private final Map<UUID, LongOpenHashSet> cells = new HashMap<>();

	/** The owner reports this cell as ground. True when nobody claimed it before (the barrier is new). */
	boolean claim(final UUID owner, final long cell) {
		Set<UUID> set = this.owners.get(cell);
		boolean first = set == null;
		if (first) {
			set = new HashSet<>(2);
			this.owners.put(cell, set);
		}

		set.add(owner);
		this.cells.computeIfAbsent(owner, o -> new LongOpenHashSet()).add(cell);
		return first;
	}

	/** The owner no longer reports this cell. True when it had claimed it and nobody else does (the barrier goes). */
	boolean release(final UUID owner, final long cell) {
		Set<UUID> set = this.owners.get(cell);
		if (set == null || !set.remove(owner)) {
			return false;
		}

		LongOpenHashSet mine = this.cells.get(owner);
		if (mine != null) {
			mine.remove(cell);
		}

		if (!set.isEmpty()) {
			return false;
		}

		this.owners.remove(cell);
		return true;
	}

	/** Some host reports this cell as ground. */
	boolean claimed(final long cell) {
		return this.owners.containsKey(cell);
	}

	/** The owner lets go of everything (clear, or its session ended): the cells nobody else claims, whose barriers go. */
	long[] releaseAll(final UUID owner) {
		LongOpenHashSet mine = this.cells.remove(owner);
		if (mine == null) {
			return new long[0];
		}

		LongArrayList orphaned = new LongArrayList();
		for (long cell : mine) {
			Set<UUID> set = this.owners.get(cell);
			if (set != null && set.remove(owner) && set.isEmpty()) {
				this.owners.remove(cell);
				orphaned.add(cell);
			}
		}

		return orphaned.toLongArray();
	}

	/** Forgets every claim and returns every cell (the world is about to be saved). */
	long[] clear() {
		long[] all = this.owners.keySet().toLongArray();
		this.owners.clear();
		this.cells.clear();
		return all;
	}

	int size() {
		return this.owners.size();
	}
}
