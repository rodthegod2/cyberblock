package dev.cyberblock.net;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Request ids of one session (megaplan section 1.4): a request (scan, hack, devact, spawn) sent DOWN to a host carries an id; the
 * reply UP carries it back and is routed to the player who asked. The last 256 ids are remembered; older ones expire. Ids are
 * 1..Integer.MAX_VALUE and wrap. Server thread only.
 */
public final class RequestIds {
	static final int KEEP = 256;
	private final int[] ids = new int[KEEP];
	private final UUID[] askers = new UUID[KEEP];
	private int next = 1;

	/** A new id routed back to the asker; the oldest of the 256 kept ids is forgotten. */
	public int issue(final UUID asker) {
		int id = this.next;
		this.next = id == Integer.MAX_VALUE ? 1 : id + 1;
		int slot = Math.floorMod(id, KEEP);
		this.ids[slot] = id;
		this.askers[slot] = asker;
		return id;
	}

	/** Who asked for this id, or null when the id was never issued or has expired. */
	public @Nullable UUID asker(final int id) {
		if (id <= 0) {
			return null;
		}

		int slot = Math.floorMod(id, KEEP);
		return this.ids[slot] == id ? this.askers[slot] : null;
	}

	/** The id was issued by this session and has not expired (a reply UP must carry one, section 1.6). */
	public boolean issued(final int id) {
		return this.asker(id) != null;
	}
}
