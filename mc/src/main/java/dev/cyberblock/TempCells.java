package dev.cyberblock;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Iterator;

/**
 * The barriers placed for the host's {"t":"pcells"} (Night City surfaces on a projectile's path), by packed cell, with the server
 * tick they go at. A cell the probes report as ground meanwhile is promoted: it is ground from then on and never expires. No
 * Minecraft world in here: WorldBridge places and removes the blocks.
 */
final class TempCells {
	/** pcells barriers stand for 3 seconds. */
	static final int TTL_TICKS = 60;
	private final Long2LongOpenHashMap until = new Long2LongOpenHashMap();

	/** Keeps (or keeps longer) a cell until tick now + TTL_TICKS. */
	void put(final long cell, final long now) {
		this.until.put(cell, now + TTL_TICKS);
	}

	boolean contains(final long cell) {
		return this.until.containsKey(cell);
	}

	/** The probes made this cell ground: it is no longer ours to remove. True when it was one of ours. */
	boolean promote(final long cell) {
		if (!this.until.containsKey(cell)) {
			return false;
		}

		this.until.remove(cell);
		return true;
	}

	/** Forgets and returns the cells whose time is up at tick now. */
	long[] expire(final long now) {
		LongArrayList out = new LongArrayList();
		for (Iterator<Long2LongMap.Entry> it = this.until.long2LongEntrySet().iterator(); it.hasNext();) {
			Long2LongMap.Entry e = it.next();
			if (e.getLongValue() <= now) {
				out.add(e.getLongKey());
				it.remove();
			}
		}

		return out.toLongArray();
	}

	/** Forgets and returns every cell. */
	long[] clear() {
		long[] all = this.until.keySet().toLongArray();
		this.until.clear();
		return all;
	}

	int size() {
		return this.until.size();
	}
}
