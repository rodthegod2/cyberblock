package dev.cyberblock;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Iterator;
import java.util.function.LongPredicate;

/**
 * Outline blocks a creative player broke (plan Q-B6), by packed cell, until they come back. SNAP_BACK: put back SNAP_BACK_TICKS
 * after the break if the cell is still air and still Night City ground. KEEP ("Creative can erase the replica permanently"):
 * left broken. Either way a new probe report of the cell puts the barrier back at once (WorldBridge.solid places barriers only
 * into air), a probe "unground" of it means Night City has no ground there any more, and a clear or the world stopping forgets
 * everything. No Minecraft world in here: WorldBridge places the blocks. Server thread only.
 */
final class OutlineGuard {
	/** 10 seconds. */
	static final int SNAP_BACK_TICKS = 200;

	private record Broken(long tick, OutlineRules.Decision decision) {
	}

	private final Long2ObjectOpenHashMap<Broken> cells = new Long2ObjectOpenHashMap<>();

	/** A player broke the outline block at cell on tick, with this decision (SNAP_BACK or KEEP; DENY never breaks). */
	void broke(final long cell, final long tick, final OutlineRules.Decision decision) {
		if (decision != OutlineRules.Decision.DENY) {
			this.cells.put(cell, new Broken(tick, decision));
		}
	}

	/** The probes reported the cell as ground again: it is forgotten (the report puts it back). True when it was one of ours. */
	boolean reported(final long cell) {
		return this.cells.remove(cell) != null;
	}

	/** The probes no longer report the cell as ground: there is nothing to put back. */
	void unclaimed(final long cell) {
		this.cells.remove(cell);
	}

	/**
	 * The SNAP_BACK cells to put back at tick now: broken SNAP_BACK_TICKS ago or more, still air and free (no player in the cell).
	 * They are forgotten. A cell that is no longer air (a player built there) is forgotten without coming back; a cell with a player
	 * in it waits.
	 */
	long[] due(final long now, final LongPredicate air, final LongPredicate free) {
		LongArrayList out = new LongArrayList();
		for (Iterator<Long2ObjectMap.Entry<Broken>> it = this.cells.long2ObjectEntrySet().iterator(); it.hasNext();) {
			Long2ObjectMap.Entry<Broken> e = it.next();
			Broken b = e.getValue();
			if (b.decision() != OutlineRules.Decision.SNAP_BACK || now - b.tick() < SNAP_BACK_TICKS) {
				continue;
			}

			long cell = e.getLongKey();
			if (!air.test(cell)) {
				it.remove();
			} else if (free.test(cell)) {
				out.add(cell);
				it.remove();
			}
		}

		return out.toLongArray();
	}

	boolean tracks(final long cell) {
		return this.cells.containsKey(cell);
	}

	int size() {
		return this.cells.size();
	}

	void clear() {
		this.cells.clear();
	}
}
