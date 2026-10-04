package dev.cyberblock.replica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.ServerNet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The shared Night City map on the server (megaplan task 3-R2): every host's {@code terrain} (BOTH from Phase 3) goes into one map
 * kept with the world in {@code <world>/cyberblock/nightcity-map/}, in the client Atlas's region format (2-m cells, tiles of 64 x 64
 * cells, region files {@code r.<rx>.<rz>.cbat} of 8 x 8 tiles: "CBAT", version 1, then deflated {count, (index, 4096 shorts) *
 * count}; a cell is 0 unknown, else {@code ((y - MIN_Y + 1) << 1) | water}), so every client's Atlas and the Phase 7 Holo-Table
 * share it.
 *
 * <p>Clients ask for tiles with {@code cyberblock:act t:"maptile"} {@code {"tiles":[tx, tz, ...], "have":[v, ...]?}} (at most
 * {@link #MAX_REQUEST} tiles, {@code have} the version the client holds of each, parallel, optional) and get one {@code cyberblock:ui
 * t:"maptile"} per tile it does not have yet: {@code {"tx", "tz", "v", "c"}} where {@code v} is the tile's version (a CRC32 of its
 * cells, 1..2^32-1; 0 = the server knows nothing there and {@code c} is absent) and {@code c} the base64 of its 4096 cells as
 * big-endian shorts, x-major from the north-west corner ({@code c[z * 64 + x]}). A tile whose version equals {@code have} is not
 * sent again: polling the tiles around the player every few seconds costs nothing until the map changes.
 *
 * <p>Server thread only, except the file writes (one background thread, in order).
 */
public final class NightCityMap {
	public static final int CELL = 2, TILE = 64, REGION = 8, MIN_Y = -4000, MAX_Y = 12000;
	static final int MAGIC = 0x43424154, VERSION = 1;
	/** Tiles in one request, tiles a player may ask for per second (and the burst). */
	static final int MAX_REQUEST = 32, TILES_PER_SECOND = 64, TILE_BURST = 128;
	/** A terrain message holds whole quadruples, at most this many cells (the protocol's 64, with room for a relay's batching). */
	static final int MAX_CELLS = 1024;
	private static final int SAVE_TICKS = 600;
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cbat");

	/** The cells: pure, no world. */
	static final class Tiles {
		private final Long2ObjectOpenHashMap<short[]> tiles = new Long2ObjectOpenHashMap<>();
		private final LongOpenHashSet dirty = new LongOpenHashSet();
		private final Long2LongOpenHashMap versions = new Long2LongOpenHashMap();

		static short encode(final int y, final boolean water) {
			return (short) (((Math.clamp(y, MIN_Y, MAX_Y) - MIN_Y + 1) << 1) | (water ? 1 : 0));
		}

		static int height(final short cell) {
			return ((cell & 0xFFFF) >> 1) - 1 + MIN_Y;
		}

		static long key(final int a, final int b) {
			return ((long) a << 32) | (b & 0xFFFFFFFFL);
		}

		/** Stores a cell; true when it changed. */
		boolean put(final int cx, final int cz, final int y, final boolean water) {
			int tx = Math.floorDiv(cx, TILE), tz = Math.floorDiv(cz, TILE);
			long k = key(tx, tz);
			short value = encode(y, water);
			int index = Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE);
			short[] tile = this.tiles.get(k);
			if (tile != null && tile[index] == value) {
				return false;
			}

			if (tile == null) {
				tile = new short[TILE * TILE];
				this.tiles.put(k, tile);
			}

			tile[index] = value;
			this.versions.remove(k);
			this.dirty.add(key(Math.floorDiv(tx, REGION), Math.floorDiv(tz, REGION)));
			return true;
		}

		/** The cell's packed value, 0 when unknown. */
		short get(final int cx, final int cz) {
			short[] tile = this.tiles.get(key(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE)));
			return tile == null ? 0 : tile[Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE)];
		}

		short @Nullable [] tile(final int tx, final int tz) {
			return this.tiles.get(key(tx, tz));
		}

		/** The tile's version: 0 when it does not exist, else a CRC32 of its cells (never 0). */
		long version(final int tx, final int tz) {
			long k = key(tx, tz);
			short[] tile = this.tiles.get(k);
			if (tile == null) {
				return 0L;
			}

			return this.versions.computeIfAbsent(k, x -> {
				CRC32 crc = new CRC32();
				crc.update(bytes(tile));
				return Math.max(1L, crc.getValue());
			});
		}

		int size() {
			return this.tiles.size();
		}

		static byte[] bytes(final short[] tile) {
			ByteBuffer b = ByteBuffer.allocate(tile.length * 2);
			b.asShortBuffer().put(tile);
			return b.array();
		}

		/** The changed regions with their file content; they count as clean afterwards. */
		Map<Long, byte[]> takeDirty() {
			Map<Long, byte[]> out = new HashMap<>();
			for (long region : this.dirty) {
				out.put(region, this.encodeRegion((int) (region >> 32), (int) region));
			}

			this.dirty.clear();
			return out;
		}

		byte[] encodeRegion(final int rx, final int rz) {
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream header = new DataOutputStream(bytes);
				header.writeInt(MAGIC);
				header.writeInt(VERSION);
				header.flush();
				int count = 0;
				for (int i = 0; i < REGION * REGION; i++) {
					count += this.tiles.containsKey(key(rx * REGION + i % REGION, rz * REGION + i / REGION)) ? 1 : 0;
				}

				try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
					out.writeShort(count);
					for (int i = 0; i < REGION * REGION; i++) {
						short[] tile = this.tiles.get(key(rx * REGION + i % REGION, rz * REGION + i / REGION));
						if (tile != null) {
							out.writeByte(i);
							out.write(bytes(tile));
						}
					}
				}

				return bytes.toByteArray();
			} catch (IOException e) {
				throw new IllegalStateException("in-memory stream", e);
			}
		}

		/** Loads a region file's tiles; false (and nothing loaded) when the content is not one. */
		boolean decodeRegion(final int rx, final int rz, final byte[] content) {
			Map<Long, short[]> loaded = new HashMap<>();
			try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(content))) {
				if (header.readInt() != MAGIC || header.readInt() != VERSION) {
					return false;
				}

				try (DataInputStream in = new DataInputStream(new InflaterInputStream(header))) {
					int count = in.readUnsignedShort();
					if (count > REGION * REGION) {
						return false;
					}

					for (int n = 0; n < count; n++) {
						int i = in.readUnsignedByte();
						if (i >= REGION * REGION) {
							return false;
						}

						short[] tile = new short[TILE * TILE];
						for (int c = 0; c < tile.length; c++) {
							tile[c] = in.readShort();
						}

						loaded.put(key(rx * REGION + i % REGION, rz * REGION + i / REGION), tile);
					}
				}
			} catch (IOException e) {
				return false;
			}

			loaded.forEach((k, tile) -> {
				this.tiles.put((long) k, tile);
				this.versions.remove((long) k);
			});
			return true;
		}
	}

	private static final Tiles tiles = new Tiles();
	private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Cyberblock night city map saver");
		t.setDaemon(true);
		return t;
	});
	private static final Map<UUID, double[]> buckets = new HashMap<>();
	private static @Nullable Path dir;
	private static int bad;

	private NightCityMap() {
	}

	/** Server start: loads the world's map. */
	static void attach(final MinecraftServer server) {
		open(server.getWorldPath(LevelResource.ROOT).resolve(Cyberblock.ID).resolve("nightcity-map"));
	}

	static void open(final Path folder) {
		tiles.tiles.clear();
		tiles.versions.clear();
		tiles.dirty.clear();
		buckets.clear();
		dir = folder;
		int regions = 0;
		if (Files.isDirectory(folder)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "r.*.cbat")) {
				for (Path file : files) {
					Matcher m = REGION_FILE.matcher(file.getFileName().toString());
					if (m.matches() && tiles.decodeRegion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Files.readAllBytes(file))) {
						regions++;
					} else {
						Cyberblock.LOG.warn("replica: {} is not a map region file, skipped", file.getFileName());
					}
				}
			} catch (IOException | NumberFormatException e) {
				Cyberblock.LOG.warn("replica: could not read the Night City map: {}", e.toString());
			}
		}

		Cyberblock.LOG.info("replica: Night City map opened ({} regions, {} tiles)", regions, tiles.size());
	}

	/** Server stop: everything is written now, and the map is forgotten. */
	static void detach() {
		Path folder = dir;
		if (folder != null) {
			Map<Long, byte[]> regions = tiles.takeDirty();
			try {
				SAVER.submit(() -> write(folder, regions)).get(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				Cyberblock.LOG.warn("replica: the last save of the Night City map did not finish: {}", e.toString());
			}
		}

		dir = null;
		tiles.tiles.clear();
		tiles.versions.clear();
		buckets.clear();
	}

	/** Every server tick: the changed regions are written every 30 s (encoded here, written on the saver thread). */
	static void tick(final MinecraftServer server) {
		Path folder = dir;
		if (folder != null && server.getTickCount() % SAVE_TICKS == 0 && !tiles.dirty.isEmpty()) {
			Map<Long, byte[]> regions = tiles.takeDirty();
			SAVER.submit(() -> write(folder, regions));
		}
	}

	private static void write(final Path folder, final Map<Long, byte[]> regions) {
		try {
			Files.createDirectories(folder);
			for (Map.Entry<Long, byte[]> region : regions.entrySet()) {
				Path file = folder.resolve("r." + (int) (region.getKey() >> 32) + "." + region.getKey().intValue() + ".cbat");
				Path temp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.write(temp, region.getValue());
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (IOException e) {
			Cyberblock.LOG.warn("replica: could not save the Night City map: {}", e.toString());
		}
	}

	/** {@code terrain} UP from a host: c:[cx, cz, y, s, ...] (protocol.md "Atlas"); a bad message is dropped whole. */
	static void terrain(final HostSession session, final JsonObject m) {
		int[] c = cells(m);
		if (c == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("replica: bad terrain from {} dropped (seen {} times)", session.name(), bad);
			}

			return;
		}

		for (int i = 0; i < c.length; i += 4) {
			tiles.put(c[i], c[i + 1], c[i + 2], c[i + 3] == 1);
		}
	}

	/** terrain.c as ints: whole quadruples, at most MAX_CELLS cells, |cx|, |cz| < 1.5e7, |y| <= 20000, s 0 or 1; else null. */
	static int @Nullable [] cells(final JsonObject m) {
		JsonElement e = m.get("c");
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() % 4 != 0 || e.getAsJsonArray().size() > MAX_CELLS * 4) {
			return null;
		}

		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			JsonElement v = a.get(i);
			double d = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
			double limit = switch (i % 4) {
				case 0, 1 -> 1.5e7;
				case 2 -> 20000;
				default -> 1;
			};
			if (!(Math.abs(d) <= limit) || d != Math.rint(d) || i % 4 == 3 && d < 0) {
				return null;
			}

			out[i] = (int) d;
		}

		return out;
	}

	/** The map's highest Night City surface over this block column (its 2-m cell), Integer.MIN_VALUE when unknown. */
	public static int top(final int x, final int z) {
		short cell = tiles.get(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
		return cell == 0 ? Integer.MIN_VALUE : Tiles.height(cell);
	}

	/** The map says water over this block column. */
	public static boolean water(final int x, final int z) {
		return (tiles.get(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)) & 1) != 0;
	}

	/** {@code cyberblock:act t:"maptile"} from any player: the tiles it asked for that it does not hold yet, one ui message each. */
	static void request(final ServerPlayer player, final JsonObject m) {
		long[] asked = parseRequest(m);
		if (asked == null) {
			return;
		}

		double[] bucket = buckets.computeIfAbsent(player.getUUID(), u -> new double[] {TILE_BURST, System.currentTimeMillis()});
		long now = System.currentTimeMillis();
		bucket[0] = Math.min(TILE_BURST, bucket[0] + (now - bucket[1]) / 1000.0 * TILES_PER_SECOND);
		bucket[1] = now;
		for (int i = 0; i < asked.length; i += 3) {
			if (bucket[0] < 1) {
				return; // over the rate: the client asks again later
			}

			String json = answer((int) asked[i], (int) asked[i + 1], asked[i + 2]);
			if (json != null) {
				bucket[0]--;
				ServerNet.ui(player, "maptile", json);
			}
		}
	}

	/** The request as (tx, tz, have) triples, or null when it is not a valid one. */
	static long @Nullable [] parseRequest(final JsonObject m) {
		JsonElement t = m.get("tiles"), h = m.get("have");
		if (t == null || !t.isJsonArray() || t.getAsJsonArray().size() % 2 != 0 || t.getAsJsonArray().size() > MAX_REQUEST * 2) {
			return null;
		}

		int n = t.getAsJsonArray().size() / 2;
		if (h != null && (!h.isJsonArray() || h.getAsJsonArray().size() != n)) {
			return null;
		}

		long[] out = new long[n * 3];
		for (int i = 0; i < n; i++) {
			double tx = number(t.getAsJsonArray().get(2 * i)), tz = number(t.getAsJsonArray().get(2 * i + 1));
			double have = h == null ? 0 : number(h.getAsJsonArray().get(i));
			if (!(Math.abs(tx) <= 1 << 20) || !(Math.abs(tz) <= 1 << 20) || tx != Math.rint(tx) || tz != Math.rint(tz) || !(have >= 0 && have <= 0xFFFFFFFFL)) {
				return null;
			}

			out[3 * i] = (long) tx;
			out[3 * i + 1] = (long) tz;
			out[3 * i + 2] = (long) have;
		}

		return out;
	}

	private static double number(final JsonElement e) {
		return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
	}

	/** The ui json for tile (tx, tz), or null when the client holds that version already. */
	static @Nullable String answer(final int tx, final int tz, final long have) {
		long v = tiles.version(tx, tz);
		if (v == have) {
			return null;
		}

		JsonObject o = new JsonObject();
		o.addProperty("tx", tx);
		o.addProperty("tz", tz);
		o.addProperty("v", v);
		short[] tile = tiles.tile(tx, tz);
		if (tile != null) {
			o.addProperty("c", Base64.getEncoder().encodeToString(Tiles.bytes(tile)));
		}

		return o.toString();
	}

	/** Tests: the live store. */
	static Tiles tiles() {
		return tiles;
	}
}
