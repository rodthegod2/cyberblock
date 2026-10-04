package dev.cyberblock.client.atlas;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * The explored Night City heightmap (pure, tested): 2-m cells (protocol.md "Atlas", {@code terrain}) in tiles of 64 x 64 cells
 * (128 blocks), tiles grouped by 8 x 8 into region files (1 km). Each cell is one short: 0 unknown, else
 * {@code ((y - MIN_Y + 1) << 1) | water}. Thread-safe: every method locks the instance (the link thread writes, the render
 * thread copies tiles out to draw them, a background thread saves).
 */
public final class AtlasTiles {
	public static final int CELL = 2;
	public static final int TILE = 64;
	public static final int REGION = 8;
	public static final int MIN_Y = -4000;
	public static final int MAX_Y = 12000;
	/** The region file format: "CBAT", version 1, then deflated {count, (index, 4096 shorts) * count}. */
	static final int MAGIC = 0x43424154;
	static final int VERSION = 1;

	/** One tile: its cells x-major from the north-west corner ({@code cells[z * TILE + x]}), and a counter bumped on every change. */
	static final class Tile {
		final short[] cells = new short[TILE * TILE];
		int version;
	}

	private final Map<Long, Tile> tiles = new HashMap<>();
	private final Set<Long> dirtyRegions = new HashSet<>();
	private int version;
	private int generation;

	public static short encode(final int y, final boolean water) {
		int clamped = Math.clamp(y, MIN_Y, MAX_Y);
		return (short) (((clamped - MIN_Y + 1) << 1) | (water ? 1 : 0));
	}

	public static boolean known(final short cell) {
		return cell != 0;
	}

	public static int height(final short cell) {
		return ((cell & 0xFFFF) >> 1) - 1 + MIN_Y;
	}

	public static boolean water(final short cell) {
		return (cell & 1) != 0;
	}

	public static long key(final int a, final int b) {
		return ((long) a << 32) | (b & 0xFFFFFFFFL);
	}

	public static int keyA(final long key) {
		return (int) (key >> 32);
	}

	public static int keyB(final long key) {
		return (int) key;
	}

	/** The cell of a block coordinate, the tile of a cell, the region of a tile. */
	public static int cellOf(final double block) {
		return (int) Math.floor(block / CELL);
	}

	public static int tileOf(final int cell) {
		return Math.floorDiv(cell, TILE);
	}

	public static int regionOf(final int tile) {
		return Math.floorDiv(tile, REGION);
	}

	/** Stores a cell; true when it changed. */
	public synchronized boolean put(final int cx, final int cz, final int y, final boolean water) {
		int tx = tileOf(cx), tz = tileOf(cz);
		long k = key(tx, tz);
		Tile tile = this.tiles.get(k);
		short value = encode(y, water);
		int index = Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE);
		if (tile != null && tile.cells[index] == value) {
			return false;
		}

		if (tile == null) {
			tile = new Tile();
			this.tiles.put(k, tile);
		}

		tile.cells[index] = value;
		tile.version++;
		this.version++;
		this.dirtyRegions.add(key(regionOf(tx), regionOf(tz)));
		return true;
	}

	/** The cell's packed value, 0 when unknown. */
	public synchronized short get(final int cx, final int cz) {
		Tile tile = this.tiles.get(key(tileOf(cx), tileOf(cz)));
		return tile == null ? 0 : tile.cells[Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE)];
	}

	/** The tile's version (0 when it does not exist): a drawn tile is rebuilt when it, or a neighbour, changed. */
	public synchronized int tileVersion(final int tx, final int tz) {
		Tile tile = this.tiles.get(key(tx, tz));
		return tile == null ? 0 : tile.version;
	}

	/** The sum of the tile versions in the inclusive tile rectangle: it changes whenever one of those tiles does. */
	public synchronized long versionSum(final int tx0, final int tz0, final int tx1, final int tz1) {
		long sum = 0;
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				Tile tile = this.tiles.get(key(tx, tz));
				if (tile != null) {
					// weighted by position, so two tiles changing by the same amount in opposite places never cancel out
					sum += (long) tile.version * (1 + (tz - tz0) * 31L + (tx - tx0)) + 1;
				}
			}
		}

		return sum;
	}

	public synchronized boolean hasTile(final int tx, final int tz) {
		return this.tiles.containsKey(key(tx, tz));
	}

	/** Bumped on every change anywhere. */
	public synchronized int version() {
		return this.version;
	}

	public synchronized int tileCount() {
		return this.tiles.size();
	}

	/**
	 * The cells of the square of cells starting at (cx0, cz0), {@code size} wide, copied into {@code out} ({@code out[z * size + x]};
	 * unknown cells are 0). One lock for the whole copy.
	 */
	public synchronized void copy(final int cx0, final int cz0, final int size, final short[] out) {
		for (int tz = tileOf(cz0); tz <= tileOf(cz0 + size - 1); tz++) {
			for (int tx = tileOf(cx0); tx <= tileOf(cx0 + size - 1); tx++) {
				Tile tile = this.tiles.get(key(tx, tz));
				int x0 = Math.max(cx0, tx * TILE), x1 = Math.min(cx0 + size, (tx + 1) * TILE);
				int z0 = Math.max(cz0, tz * TILE), z1 = Math.min(cz0 + size, (tz + 1) * TILE);
				for (int z = z0; z < z1; z++) {
					for (int x = x0; x < x1; x++) {
						out[(z - cz0) * size + (x - cx0)] = tile == null ? 0 : tile.cells[(z - tz * TILE) * TILE + (x - tx * TILE)];
					}
				}
			}
		}
	}

	/** The share (0..1) of known cells in the inclusive cell rectangle. */
	public synchronized double coverage(final int cx0, final int cz0, final int cx1, final int cz1) {
		long known = 0, all = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
		for (int tz = tileOf(cz0); tz <= tileOf(cz1); tz++) {
			for (int tx = tileOf(cx0); tx <= tileOf(cx1); tx++) {
				Tile tile = this.tiles.get(key(tx, tz));
				if (tile == null) {
					continue;
				}

				for (int z = Math.max(cz0, tz * TILE); z <= Math.min(cz1, (tz + 1) * TILE - 1); z++) {
					for (int x = Math.max(cx0, tx * TILE); x <= Math.min(cx1, (tx + 1) * TILE - 1); x++) {
						if (tile.cells[(z - tz * TILE) * TILE + (x - tx * TILE)] != 0) {
							known++;
						}
					}
				}
			}
		}

		return all <= 0 ? 0.0 : (double) known / all;
	}

	/** The tile keys that exist, a snapshot. */
	public synchronized List<Long> tileKeys() {
		return new ArrayList<>(this.tiles.keySet());
	}

	public synchronized void clear() {
		this.tiles.clear();
		this.dirtyRegions.clear();
		this.version++;
		this.generation++;
	}

	/** Bumped by every clear() (another world): whatever was drawn from the tiles before is stale. */
	public synchronized int generation() {
		return this.generation;
	}

	// ---- region files ----

	/** The regions changed since the last call, each with its encoded file content; they count as clean afterwards. */
	public synchronized Map<Long, byte[]> takeDirtyRegions() {
		Map<Long, byte[]> out = new HashMap<>();
		for (long region : this.dirtyRegions) {
			out.put(region, this.encodeRegion(keyA(region), keyB(region)));
		}

		this.dirtyRegions.clear();
		return out;
	}

	/** The region file content of region (rx, rz). */
	public synchronized byte[] encodeRegion(final int rx, final int rz) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream header = new DataOutputStream(bytes);
			header.writeInt(MAGIC);
			header.writeInt(VERSION);
			header.flush();
			List<Integer> present = new ArrayList<>();
			for (int i = 0; i < REGION * REGION; i++) {
				if (this.tiles.containsKey(key(rx * REGION + i % REGION, rz * REGION + i / REGION))) {
					present.add(i);
				}
			}

			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
				out.writeShort(present.size());
				for (int i : present) {
					Tile tile = this.tiles.get(key(rx * REGION + i % REGION, rz * REGION + i / REGION));
					out.writeByte(i);
					for (short cell : tile.cells) {
						out.writeShort(cell);
					}
				}
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("in-memory stream", e);
		}
	}

	/** Loads a region file's tiles (replacing tiles already there); false (and nothing loaded) when the content is not one. */
	public synchronized boolean decodeRegion(final int rx, final int rz, final byte[] content) {
		@Nullable Map<Long, Tile> loaded = decode(rx, rz, content);
		if (loaded == null) {
			return false;
		}

		this.tiles.putAll(loaded);
		this.version++;
		return true;
	}

	/**
	 * Merges a region file's tiles into what is there: a cell is taken only where this map does not know it yet (an old
	 * per-world copy merged into the shared map). The changed regions count as dirty. False when the content is not a region.
	 */
	public synchronized boolean mergeRegion(final int rx, final int rz, final byte[] content) {
		@Nullable Map<Long, Tile> loaded = decode(rx, rz, content);
		if (loaded == null) {
			return false;
		}

		for (Map.Entry<Long, Tile> e : loaded.entrySet()) {
			Tile mine = this.tiles.get(e.getKey());
			boolean changed = false;
			if (mine == null) {
				this.tiles.put(e.getKey(), e.getValue());
				changed = true;
			} else {
				for (int c = 0; c < TILE * TILE; c++) {
					if (mine.cells[c] == 0 && e.getValue().cells[c] != 0) {
						mine.cells[c] = e.getValue().cells[c];
						changed = true;
					}
				}

				if (changed) {
					mine.version++;
				}
			}

			if (changed) {
				this.version++;
				this.dirtyRegions.add(key(rx, rz));
			}
		}

		return true;
	}

	private static @Nullable Map<Long, Tile> decode(final int rx, final int rz, final byte[] content) {
		try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(content))) {
			if (header.readInt() != MAGIC || header.readInt() != VERSION) {
				return null;
			}

			Map<Long, Tile> loaded = new HashMap<>();
			try (DataInputStream in = new DataInputStream(new InflaterInputStream(header))) {
				int count = in.readUnsignedShort();
				if (count > REGION * REGION) {
					return null;
				}

				for (int n = 0; n < count; n++) {
					int i = in.readUnsignedByte();
					if (i >= REGION * REGION) {
						return null;
					}

					Tile tile = new Tile();
					for (int c = 0; c < TILE * TILE; c++) {
						tile.cells[c] = in.readShort();
					}

					tile.version = 1;
					loaded.put(key(rx * REGION + i % REGION, rz * REGION + i / REGION), tile);
				}
			}

			return loaded;
		} catch (IOException e) {
			return null;
		}
	}
}
