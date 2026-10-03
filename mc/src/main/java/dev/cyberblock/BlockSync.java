package dev.cyberblock;

import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * The block resync after a host hello: {"t":"blocks","reset":true,"c":[first cells]} at once, then the rest of the world's
 * solid blocks (PlacedBlocks), at most PER_MESSAGE cells per message and one message every TICKS_BETWEEN server ticks (5 a
 * second). A live block event for a cell that is still waiting replaces the resync's copy of it, so the host ends up with the
 * world as it is whatever the order. No Minecraft world in here: the server thread drives it (WorldBridge).
 */
final class BlockSync {
	static final int PER_MESSAGE = 64;
	static final int TICKS_BETWEEN = 4;
	private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
	private boolean resetDue;
	private int wait;

	/** A new resync of these cells, in this order; the next message is the reset (even with no cells at all). */
	void start(final long[] cells) {
		this.pending.clear();
		for (long cell : cells) {
			this.pending.add(cell);
		}

		this.resetDue = true;
		this.wait = 0;
	}

	/** No resync (the host went away or the world closed). */
	void clear() {
		this.pending.clear();
		this.resetDue = false;
		this.wait = 0;
	}

	/** A live event for this cell goes to the host by itself: the resync no longer sends it. */
	void forget(final long cell) {
		this.pending.remove(cell);
	}

	boolean running() {
		return this.resetDue || !this.pending.isEmpty();
	}

	/** Once per server tick: the message due now, or null. */
	String next() {
		if (this.wait > 0) {
			this.wait--;
			return null;
		}

		if (!this.running()) {
			return null;
		}

		long[] cells = new long[Math.min(PER_MESSAGE, this.pending.size())];
		for (int i = 0; i < cells.length; i++) {
			cells[i] = this.pending.removeFirstLong();
		}

		boolean reset = this.resetDue;
		this.resetDue = false;
		this.wait = TICKS_BETWEEN - 1;
		return Wire.blocks(reset, cells);
	}

	/** Sorts cells nearest to (x, y, z) first, so the host gets the collision around V first. */
	static void nearestFirst(final long[] cells, final int x, final int y, final int z) {
		LongArrays.quickSort(cells, (a, b) -> Long.compare(distanceSq(a, x, y, z), distanceSq(b, x, y, z)));
	}

	private static long distanceSq(final long cell, final int x, final int y, final int z) {
		long dx = BlockPos.getX(cell) - x, dy = BlockPos.getY(cell) - y, dz = BlockPos.getZ(cell) - z;
		return dx * dx + dy * dy + dz * dz;
	}
}
