package dev.cyberblock.client.atlas;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.ClientNet;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.net.Payloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * A guest's view of the server's shared Night City map (megaplan task 3-C1, against 3-R2's store): the Atlas and the radar of a
 * guest draw from these tiles instead of this installation's own map (a host keeps its own store, AtlasStore, and its
 * {@code terrain} also goes UP: BOTH). Memory only, cleared when the client leaves the server.
 *
 * <p>The tiles come one by one ({@code cyberblock:act maptile} / {@code cyberblock:ui maptile}, AtlasWire, as 3R's NightCityMap
 * serves them): what the Atlas or the radar draws asks for its tile, or a far-zoom region for its 64 tiles ({@link #want},
 * {@link #wantRegion}); the client tick sends the asks (at most 32 tiles twice a second, the server's 64 tiles a second), each tile
 * again at most every 30 s with the version it has, and the server answers only the tiles that changed. Client thread (the render
 * thread is the client thread).
 */
public final class ServerMap {
	/** The shared map as far as this guest has seen it. */
	static final AtlasTiles tiles = new AtlasTiles();
	private static final int SEND_TICKS = 10;
	private static final Requests requests = new Requests(30_000_000_000L);
	private static final AtomicInteger bad = new AtomicInteger();
	/** The server answered a maptile on this connection: it has the shared map (a Phase 3 server), even if hs_ack does not say so. */
	private static boolean answered;
	private static int ticks;

	/**
	 * Which tiles to ask for and when (pure, tested): a tile drawn now is asked once, then again at most every {@code refreshNanos}
	 * with the version this client has, so a changed tile arrives within that time. A far-zoom region asks for its 64 tiles at most
	 * as often.
	 */
	static final class Requests {
		private final long refreshNanos;
		private final LinkedHashSet<Long> wanted = new LinkedHashSet<>();
		private final Map<Long, Long> askedAt = new HashMap<>();
		private final Map<Long, Long> regionAskedAt = new HashMap<>();
		private final Map<Long, Long> versions = new HashMap<>();

		Requests(final long refreshNanos) {
			this.refreshNanos = refreshNanos;
		}

		/** Tile (tx, tz) is drawn now: queued unless it was asked for less than refreshNanos ago. */
		void want(final int tx, final int tz, final long now) {
			long key = AtlasTiles.key(tx, tz);
			Long at = this.askedAt.get(key);
			if (at == null || now - at >= this.refreshNanos) {
				this.wanted.add(key);
			}
		}

		/** Region (rx, rz) is drawn at far zoom now: its 64 tiles are queued, at most every refreshNanos. */
		void wantRegion(final int rx, final int rz, final long now) {
			long key = AtlasTiles.key(rx, rz);
			Long at = this.regionAskedAt.get(key);
			if (at != null && now - at < this.refreshNanos) {
				return;
			}

			this.regionAskedAt.put(key, now);
			for (int i = 0; i < AtlasTiles.REGION * AtlasTiles.REGION; i++) {
				this.want(rx * AtlasTiles.REGION + i % AtlasTiles.REGION, rz * AtlasTiles.REGION + i / AtlasTiles.REGION, now);
			}
		}

		/** Up to {@code max} queued tiles as {tx, tz, version this client has}; they count as asked now. */
		List<long[]> take(final int max, final long now) {
			List<long[]> out = new ArrayList<>();
			for (Iterator<Long> it = this.wanted.iterator(); it.hasNext() && out.size() < max;) {
				long key = it.next();
				it.remove();
				this.askedAt.put(key, now);
				out.add(new long[] {AtlasTiles.keyA(key), AtlasTiles.keyB(key), this.versions.getOrDefault(key, 0L)});
			}

			return out;
		}

		/** The server's answer for a tile: its version is what the next ask says this client has. */
		void answered(final int tx, final int tz, final long v) {
			this.versions.put(AtlasTiles.key(tx, tz), v);
		}

		void clear() {
			this.wanted.clear();
			this.askedAt.clear();
			this.regionAskedAt.clear();
			this.versions.clear();
		}
	}

	private ServerMap() {
	}

	static void register() {
		UiRoutes.listen("maptile", ServerMap::onUi);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(ServerMap::clear));
		ClientTickEvents.END_CLIENT_TICK.register(ServerMap::tick);
	}

	/** This client asks the server for map tiles: it plays as a guest on a server (any server; one without the map ignores the ask). */
	static boolean asking() {
		return ClientNet.role() == ClientNet.Role.GUEST;
	}

	/** A guest's Atlas and radar draw from the shared map: the server has it (hs_ack's replica feature, or it answered). */
	static boolean active() {
		return asking() && ((ClientNet.features() & Payloads.FEATURE_REPLICA) != 0 || answered);
	}

	/** AtlasTextures draws tile (tx, tz) for a guest. */
	static void want(final int tx, final int tz) {
		requests.want(tx, tz, System.nanoTime());
	}

	/** AtlasTextures draws region (rx, rz) at far zoom for a guest. */
	static void wantRegion(final int rx, final int rz) {
		requests.wantRegion(rx, rz, System.nanoTime());
	}

	private static void tick(final Minecraft minecraft) {
		if (++ticks % SEND_TICKS != 0 || !asking() || !ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			return;
		}

		List<long[]> due = requests.take(AtlasWire.MAX_MAPTILE_TILES, System.nanoTime());
		if (!due.isEmpty()) {
			ClientPlayNetworking.send(new Payloads.Act("maptile", AtlasWire.mapTileRequest(due)));
		}
	}

	/** {@code cyberblock:ui maptile} (client thread). */
	static void onUi(final String json) {
		AtlasWire.MapTile tile;
		try {
			tile = AtlasWire.parseMapTile(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			tile = null;
		}

		if (tile == null) {
			if (bad.getAndIncrement() % 100 == 0) {
				Cyberblock.LOG.warn("atlas: bad maptile dropped ({} so far)", bad.get());
			}

			return;
		}

		if (!answered) {
			answered = true;
			Cyberblock.LOG.info("atlas: the server shares its Night City map: this guest's Atlas and radar draw from it");
		}

		apply(tiles, tile);
		requests.answered(tile.tx(), tile.tz(), tile.v());
	}

	/** Puts one answered tile into {@code into}; an answer without cells (the server knows nothing there) changes nothing. */
	static void apply(final AtlasTiles into, final AtlasWire.MapTile tile) {
		if (tile.cells() != null) {
			into.putTile(tile.tx(), tile.tz(), tile.cells());
		}
	}

	private static void clear() {
		tiles.clear();
		requests.clear();
		answered = false;
	}
}
