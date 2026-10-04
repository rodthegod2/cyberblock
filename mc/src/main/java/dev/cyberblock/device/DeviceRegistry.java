package dev.cyberblock.device;

import dev.cyberblock.net.Permissions.DeviceType;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Every host's devices (megaplan 4-M1): one table per owner session, keyed by (owner, Night City id), the cells their blocks hold and
 * the boxes the replica keeps out of. Each device is drawn through {@link DeviceShape}; a cell is taken only when
 * {@link DeviceRules#mayPlace} allows it, and given back (to air) only while it still holds this device's block. The world goes
 * through {@link Cells}, so the rules run in tests without a level. Server thread only.
 */
public final class DeviceRegistry {
	/** The world as the registry sees it: a cell's state, and setting one (as Night City's own change, never a player's block). */
	public interface Cells {
		BlockState get(long cell);

		void set(long cell, BlockState state);
	}

	/** How a device is drawn: its device_map entry and its resolved blocks. */
	public record Look(DeviceMap.Entry entry, DeviceShape.Blocks blocks) {
	}

	/** A device id is only meaningful with its owner. */
	public record Key(UUID owner, long id) {
	}

	/** One tracked device: its newest data, when a list last named it, the cells it holds and its box. */
	public static final class Tracked {
		final Key key;
		Device device;
		long seen;
		final Map<Long, BlockState> placed = new LinkedHashMap<>();
		LongSet box = new LongLinkedOpenHashSet();

		Tracked(final Key key, final Device device, final long seen) {
			this.key = key;
			this.device = device;
			this.seen = seen;
		}

		public Key key() {
			return this.key;
		}

		public Device device() {
			return this.device;
		}

		/** The cells this device's blocks stand in now (read only). */
		public Map<Long, BlockState> placed() {
			return Map.copyOf(this.placed);
		}
	}

	/** What one call changed: devices added and removed, cells set. */
	public record Change(int added, int removed, int cells) {
		static final Change NONE = new Change(0, 0, 0);

		Change plus(final Change o) {
			return new Change(this.added + o.added, this.removed + o.removed, this.cells + o.cells);
		}

		public boolean any() {
			return this.added + this.removed + this.cells > 0;
		}
	}

	private final Map<Key, Tracked> devices = new HashMap<>();
	private final Long2ObjectOpenHashMap<Key> cellOwner = new Long2ObjectOpenHashMap<>();
	/** Box cells and how many boxes hold each. */
	private final Long2IntOpenHashMap excluded = new Long2IntOpenHashMap();
	private final Function<Device, Look> looks;

	public DeviceRegistry(final Function<Device, Look> looks) {
		this.looks = looks;
	}

	/** A devices list of this owner at tick now: new devices are placed, known ones refreshed and redrawn. */
	public Change update(final UUID owner, final List<Device> list, final long now, final Cells w) {
		Change c = Change.NONE;
		for (Device d : list) {
			Key key = new Key(owner, d.id());
			Tracked t = this.devices.get(key);
			if (t == null) {
				t = new Tracked(key, d, now);
				this.devices.put(key, t);
				c = c.plus(new Change(1, 0, 0));
			}

			t.device = d;
			t.seen = now;
			c = c.plus(this.draw(t, w));
		}

		return c;
	}

	/** A devstate or a devresult ok: the device's new state (null device when this owner has no such device). */
	public @Nullable Tracked state(final UUID owner, final long id, final int st, final int fl, final Cells w) {
		Tracked t = this.devices.get(new Key(owner, id));
		if (t != null) {
			t.device = t.device.withState(st, fl);
			this.draw(t, w);
		}

		return t;
	}

	/** Devices no list named for 10 s go (their blocks back to air). */
	public Change expire(final long now, final Cells w) {
		Change c = Change.NONE;
		for (Tracked t : List.copyOf(this.devices.values())) {
			if (DeviceRules.gone(t.seen, now)) {
				c = c.plus(this.remove(t, w));
			}
		}

		return c;
	}

	/** The owner's session ended: all its devices go now. */
	public Change end(final UUID owner, final Cells w) {
		Change c = Change.NONE;
		for (Tracked t : List.copyOf(this.devices.values())) {
			if (t.key.owner().equals(owner)) {
				c = c.plus(this.remove(t, w));
			}
		}

		return c;
	}

	/** The device whose block stands in this cell, or null. */
	public @Nullable Tracked at(final long cell) {
		Key k = this.cellOwner.get(cell);
		return k == null ? null : this.devices.get(k);
	}

	public @Nullable Tracked get(final UUID owner, final long id) {
		return this.devices.get(new Key(owner, id));
	}

	/** A cell inside some device's box (the replica never fills it). */
	public boolean excluded(final long cell) {
		return this.excluded.containsKey(cell);
	}

	/** A player broke this device block (creative): the cell is free; the next list puts the block back. */
	public void broken(final long cell) {
		Key k = this.cellOwner.remove(cell);
		Tracked t = k == null ? null : this.devices.get(k);
		if (t != null) {
			t.placed.remove(cell);
		}
	}

	public int size() {
		return this.devices.size();
	}

	public int size(final UUID owner) {
		return this.of(owner).size();
	}

	/** This owner's devices. */
	public List<Tracked> of(final UUID owner) {
		return this.devices.values().stream().filter(t -> t.key.owner().equals(owner)).toList();
	}

	/** Forget everything without touching the world (server stop after the sessions ended, tests). */
	public void clear() {
		this.devices.clear();
		this.cellOwner.clear();
		this.excluded.clear();
	}

	/**
	 * Puts the device's blocks where they belong now: cells it no longer wants go back to air, wanted cells are set when allowed
	 * (a door all at once or not at all, so a lone half never stands), and its box moves with it. Returns the cells set.
	 */
	private Change draw(final Tracked t, final Cells w) {
		Look look = this.looks.apply(t.device);
		Map<Long, BlockState> want = DeviceShape.cells(t.device, look.entry(), look.blocks());
		int set = 0;
		for (Long cell : List.copyOf(t.placed.keySet())) {
			if (!want.containsKey(cell)) {
				set += this.release(t, cell, w) ? 1 : 0;
			}
		}

		Set<Long> allowed = new LinkedHashSet<>();
		for (Map.Entry<Long, BlockState> e : want.entrySet()) {
			long cell = e.getKey();
			Key holder = this.cellOwner.get(cell);
			if (DeviceRules.mayPlace(w.get(cell), e.getValue(), holder == null ? null : holder.equals(t.key), t.placed.get(cell))) {
				allowed.add(cell);
			} else if (t.placed.containsKey(cell)) {
				t.placed.remove(cell); // a player's block took it: no longer ours
				this.cellOwner.remove(cell);
			}
		}

		// a door stands whole or not at all (its attachments, the lock lamp, may be missing)
		boolean whole = want.entrySet().stream().allMatch(e -> allowed.contains(e.getKey()) || e.getValue().getBlock() != look.blocks().block().getBlock());
		if (whole || t.device.type() != DeviceType.DOOR) {
			for (long cell : allowed) {
				BlockState state = want.get(cell);
				if (!w.get(cell).equals(state)) {
					w.set(cell, state);
					set++;
				}

				t.placed.put(cell, state);
				this.cellOwner.put(cell, t.key);
			}
		}

		LongSet box = DeviceShape.box(t.device, look.entry());
		box.addAll(want.keySet());
		this.unbox(t.box);
		t.box = box;
		for (long cell : box) {
			this.excluded.addTo(cell, 1);
		}

		return new Change(0, 0, set);
	}

	private Change remove(final Tracked t, final Cells w) {
		int set = 0;
		for (Long cell : List.copyOf(t.placed.keySet())) {
			set += this.release(t, cell, w) ? 1 : 0;
		}

		this.unbox(t.box);
		this.devices.remove(t.key);
		return new Change(0, 1, set);
	}

	/** Gives one cell back: to air while it still holds this device's block. True when the world changed. */
	private boolean release(final Tracked t, final long cell, final Cells w) {
		BlockState mine = t.placed.remove(cell);
		this.cellOwner.remove(cell);
		if (mine != null && w.get(cell).getBlock() == mine.getBlock()) {
			w.set(cell, Blocks.AIR.defaultBlockState());
			return true;
		}

		return false;
	}

	private void unbox(final LongSet box) {
		for (long cell : box) {
			if (this.excluded.addTo(cell, -1) <= 1) {
				this.excluded.remove(cell);
			}
		}
	}
}
