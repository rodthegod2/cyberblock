package dev.cyberblock;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/**
 * One host's share of the world's block changes (plan 2026-10-04 section 1.4, task 1-W1). A change is queued for the host while its
 * player is within RANGE of the cell (horizontally) and kept back until the player comes within RANGE otherwise, so a host far away
 * is not flooded and still learns the change before it matters. After the host's hello (or a re-alignment) its resync starts in one
 * tick: {"t":"align"} immediately followed by {"t":"blocks","reset":true} (re-test 2 rule), then the rest (BlockSync). No Minecraft
 * world in here: WorldBridge feeds it on the server thread.
 */
final class BlockFeed {
	/** Block changes reach hosts whose player is within this many blocks of the cell (horizontally). */
	static final int RANGE = 256;
	/** Changes kept back for a far player; past this the host simply gets a whole resync instead. */
	static final int MAX_FAR = 65_536;
	/** How often (server ticks) the kept-back changes are checked against the player's position. */
	static final int FAR_CHECK_TICKS = 20;
	private final BlockEvents.Outbox near = new BlockEvents.Outbox();
	private final Long2ObjectLinkedOpenHashMap<BlockEvents.Op> far = new Long2ObjectLinkedOpenHashMap<>();
	private final BlockSync resync = new BlockSync();
	/** A resync is owed (a hello, too many kept-back changes): it starts on the first tick that sees the host active. */
	private boolean resyncDue;
	/** A change the host never heard of (it was not active then): resync once it is again. */
	private boolean missed;
	private boolean wasActive;
	private int farCheck;

	/** The cell is within RANGE of (x, z), horizontally. */
	static boolean near(final long cell, final int x, final int z) {
		long dx = BlockPos.getX(cell) - (long) x, dz = BlockPos.getZ(cell) - (long) z;
		return dx * dx + dz * dz <= (long) RANGE * RANGE;
	}

	/** The host said hello: it knows none of our blocks, and gets every one again once it is active. */
	void hello() {
		this.resyncDue = true;
	}

	/** A change of a cell that matters to the host's collision; (x, z) is where its player is. */
	void change(final long cell, final BlockEvents.Op op, final boolean active, final int x, final int z) {
		this.resync.forget(cell);
		if (!active) {
			this.missed = true;
			return;
		}

		if (near(cell, x, z)) {
			this.far.remove(cell);
			this.near.put(cell, op);
		} else if (this.far.size() < MAX_FAR || this.far.containsKey(cell)) {
			this.far.put(cell, op);
		} else {
			this.far.clear();
			this.resyncDue = true;
		}
	}

	/**
	 * Starts the resync now: align, then the reset with the first cells (nearest to (x, y, z) first), both into sink at once.
	 * cells is a copy of the world's solid blocks (it gets sorted).
	 */
	void start(final int k, final long[] cells, final int x, final int y, final int z, final Consumer<String> sink) {
		BlockSync.nearestFirst(cells, x, y, z);
		this.near.clear();
		this.far.clear();
		this.missed = false;
		this.resyncDue = false;
		this.resync.start(cells);
		sink.accept(Wire.align(k));
		String first = this.resync.next();
		if (first != null) {
			sink.accept(first);
		}
	}

	/** The host is owed a resync on its next active tick. */
	boolean resyncWanted(final boolean active) {
		return active && (this.resyncDue || !this.wasActive && this.missed);
	}

	/**
	 * Once per server tick, after any start: the resync's next message, then (while the host is active) a few live changes near its
	 * player at (x, z). Changes queued while it is not active are dropped and owe it a resync instead.
	 */
	void tick(final boolean active, final int x, final int z, final Consumer<String> sink) {
		this.wasActive = active;
		String blocks = this.resync.next();
		if (blocks != null) {
			sink.accept(blocks);
		}

		if (!active) {
			if (this.near.size() > 0 || !this.far.isEmpty()) {
				this.missed = true; // nobody is listening: no stale replay later, a resync instead
			}

			this.near.clear();
			this.far.clear();
			return;
		}

		if (++this.farCheck >= FAR_CHECK_TICKS) {
			this.farCheck = 0;
			for (ObjectIterator<Long2ObjectMap.Entry<BlockEvents.Op>> it = this.far.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
				Long2ObjectMap.Entry<BlockEvents.Op> e = it.next();
				if (near(e.getLongKey(), x, z)) {
					this.near.put(e.getLongKey(), e.getValue());
					it.remove();
				}
			}
		}

		this.near.drain(BlockEvents.PER_TICK, (pos, op) -> sink.accept(Wire.block(op, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos))));
	}

	int kept() {
		return this.far.size();
	}
}
