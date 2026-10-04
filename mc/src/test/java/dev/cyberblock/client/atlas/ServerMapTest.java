package dev.cyberblock.client.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 3-C1: a guest's Atlas and radar draw from the server's shared map tiles (cyberblock:act / cyberblock:ui maptile). */
class ServerMapTest {
	private static final long S = 1_000_000_000L;

	private static JsonObject json(final String text) {
		return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
	}

	private static String c64(final short[] cells) {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(cells.length * 2);
		b.asShortBuffer().put(cells);
		return Base64.getEncoder().encodeToString(b.array());
	}

	/** 3R's NightCityMap.parseRequest: {"tiles":[tx, tz, ...], "have":[v, ...]}, at most 32 tiles. */
	@Test
	void theRequestListsTilesAndTheVersionsThisClientHas() {
		JsonObject m = json(AtlasWire.mapTileRequest(List.of(new long[] {-2, 3, 0}, new long[] {5, -1, 4294967295L})));
		assertEquals(json("{'tiles':[-2,3,5,-1],'have':[0,4294967295]}"), m);
		assertThrows(IllegalArgumentException.class, () -> AtlasWire.mapTileRequest(List.of()));
		List<long[]> many = new ArrayList<>();
		for (int i = 0; i <= AtlasWire.MAX_MAPTILE_TILES; i++) {
			many.add(new long[] {i, 0, 0});
		}

		assertThrows(IllegalArgumentException.class, () -> AtlasWire.mapTileRequest(many));
	}

	/** 3R's NightCityMap.answer: {"tx","tz","v","c"}, c the base64 of the 4096 cells as big-endian shorts, x-major from the north-west. */
	@Test
	void anAnswerCarriesTheTileCellsOrNothing() {
		short[] cells = new short[4096];
		cells[4 * 64 + 3] = AtlasTiles.encode(120, false);
		cells[4095] = AtlasTiles.encode(-5, true);
		AtlasWire.MapTile tile = AtlasWire.parseMapTile(json("{'tx':-1,'tz':7,'v':123456,'c':'" + c64(cells) + "'}"));
		assertNotNull(tile);
		assertEquals(-1, tile.tx());
		assertEquals(7, tile.tz());
		assertEquals(123456, tile.v());
		assertArrayEquals(cells, tile.cells());
		AtlasWire.MapTile none = AtlasWire.parseMapTile(json("{'tx':4,'tz':4,'v':0}"));
		assertNotNull(none);
		assertNull(none.cells());
	}

	@Test
	void aBrokenAnswerIsRefused() {
		String c = c64(new short[4096]);
		assertNull(AtlasWire.parseMapTile(json("{'tz':0,'v':1,'c':'" + c + "'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':0.5,'tz':0,'v':1,'c':'" + c + "'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':-1,'c':'" + c + "'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':4294967296,'c':'" + c + "'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':" + (AtlasWire.MAX_TILE + 1) + ",'tz':0,'v':1,'c':'" + c + "'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':1}")), "a known tile without its cells");
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':1,'c':'not base64!'}")));
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':1,'c':'AAAA'}")), "not 4096 cells");
		assertNull(AtlasWire.parseMapTile(json("{'tx':0,'tz':0,'v':1,'c':12}")));
	}

	@Test
	void anAnsweredTileLandsInTheGuestsMapAndARefreshRebuildsIt() {
		short[] cells = new short[4096];
		cells[4 * 64 + 3] = AtlasTiles.encode(120, false);
		AtlasTiles guest = new AtlasTiles();
		ServerMap.apply(guest, new AtlasWire.MapTile(0, 0, 1, cells));
		assertEquals(120, AtlasTiles.height(guest.get(3, 4)));
		int before = guest.tileVersion(0, 0);
		// the host explored more: the same tile again with more cells; its version moves on, so the texture is rebuilt
		cells[4 * 64 + 5] = AtlasTiles.encode(80, true);
		ServerMap.apply(guest, new AtlasWire.MapTile(0, 0, 2, cells));
		assertTrue(guest.tileVersion(0, 0) > before);
		assertTrue(AtlasTiles.water(guest.get(5, 4)));
		// the tile of a negative coordinate lands where AtlasTiles keeps it
		ServerMap.apply(guest, new AtlasWire.MapTile(-1, -1, 3, cells));
		assertEquals(120, AtlasTiles.height(guest.get(-64 + 3, -64 + 4)));
		// an answer without cells (the server knows nothing there) changes nothing
		ServerMap.apply(guest, new AtlasWire.MapTile(9, 9, 0, null));
		assertEquals(2, guest.tileCount());
	}

	@Test
	void aDrawnTileIsAskedOnceThenAtMostEveryRefresh() {
		ServerMap.Requests r = new ServerMap.Requests(30 * S);
		r.want(1, 2, 0);
		r.want(1, 2, 0);
		r.want(-3, 0, 0);
		List<long[]> first = r.take(16, 0);
		assertEquals(2, first.size());
		// the most recently drawn first
		assertArrayEquals(new long[] {-3, 0, 0}, first.get(0));
		assertArrayEquals(new long[] {1, 2, 0}, first.get(1));
		// drawn every frame afterwards: not asked again before the refresh time
		r.want(1, 2, 10 * S);
		assertTrue(r.take(16, 10 * S).isEmpty());
		// the answer's version goes with the next ask, so the server only sends a changed region
		r.answered(1, 2, 9);
		r.want(1, 2, 31 * S);
		List<long[]> again = r.take(16, 31 * S);
		assertEquals(1, again.size());
		assertArrayEquals(new long[] {1, 2, 9}, again.get(0));
	}

	/** A far-zoom region asks for its 64 tiles, at most every refresh. */
	@Test
	void aFarZoomRegionAsksForItsSixtyFourTiles() {
		ServerMap.Requests r = new ServerMap.Requests(30 * S);
		r.wantRegion(-1, 2, 0);
		r.wantRegion(-1, 2, 0);
		List<long[]> tiles = new ArrayList<>();
		for (List<long[]> part = r.take(32, 0); !part.isEmpty(); part = r.take(32, 0)) {
			tiles.addAll(part);
		}

		assertEquals(64, tiles.size());
		assertTrue(tiles.stream().allMatch(t -> AtlasTiles.regionOf((int) t[0]) == -1 && AtlasTiles.regionOf((int) t[1]) == 2));
		r.wantRegion(-1, 2, 29 * S);
		assertTrue(r.take(32, 29 * S).isEmpty());
		r.wantRegion(-1, 2, 30 * S);
		assertEquals(32, r.take(32, 30 * S).size());
	}

	/**
	 * A guest looked at the whole map (160 far-zoom regions, 10240 tiles) and then at the radar: the radar's tile goes in the very
	 * next ask, and regions no longer drawn leave the queue instead of keeping it busy for minutes.
	 */
	@Test
	void aTileDrawnNowGoesBeforeAWideViewsBacklog() {
		ServerMap.Requests r = new ServerMap.Requests(30 * S);
		for (int rx = 0; rx < 16; rx++) {
			for (int rz = 0; rz < 10; rz++) {
				r.wantRegion(rx, rz, 0);
			}
		}

		assertEquals(32, r.take(32, 0).size());
		r.want(500, -500, S / 2);
		List<long[]> next = r.take(32, S / 2);
		assertArrayEquals(new long[] {500, -500, 0}, next.get(0), "the newly drawn tile first");
		assertEquals(32, next.size(), "the rest of the ask is filled from the drawn regions");
		// the wide view closed: two seconds later only what is drawn now is asked
		r.want(501, -500, 3 * S);
		List<long[]> later = r.take(32, 3 * S);
		assertEquals(1, later.size());
		assertArrayEquals(new long[] {501, -500, 0}, later.get(0));
		assertTrue(r.take(32, 3 * S).isEmpty(), "the stale regions left the queue");
		// a region drawn again comes back; its tiles already asked are not asked again (63 of region (15, 9), drawn last, went)
		r.wantRegion(15, 9, 4 * S);
		List<long[]> back = r.take(32, 4 * S);
		assertEquals(1, back.size());
		assertEquals(15, AtlasTiles.regionOf((int) back.get(0)[0]));
		assertEquals(9, AtlasTiles.regionOf((int) back.get(0)[1]));
		assertTrue(r.take(32, 4 * S).isEmpty(), "a fully asked region waits for its refresh");
	}

	/** Regions too: the most recently drawn region is asked first. */
	@Test
	void theMostRecentlyDrawnRegionIsAskedFirst() {
		ServerMap.Requests r = new ServerMap.Requests(30 * S);
		r.wantRegion(0, 0, 0);
		r.wantRegion(5, 5, 0);
		r.wantRegion(0, 0, 1);
		assertTrue(r.take(32, 1).stream().allMatch(t -> AtlasTiles.regionOf((int) t[0]) == 0 && AtlasTiles.regionOf((int) t[1]) == 0));
	}

	@Test
	void atMostTheGivenNumberOfTilesGoInOneAsk() {
		ServerMap.Requests r = new ServerMap.Requests(30 * S);
		for (int i = 0; i < 40; i++) {
			r.want(i, 0, 0);
		}

		assertEquals(16, r.take(16, 0).size());
		assertEquals(16, r.take(16, 0).size());
		assertEquals(8, r.take(16, 0).size());
		r.clear();
		r.want(0, 0, 1);
		assertEquals(1, r.take(16, 1).size(), "after clear (another server) every region is asked again");
	}
}
