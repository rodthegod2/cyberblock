package dev.cyberblock.client.atlas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The explored map's storage: cell encoding, tiles across negative coordinates, coverage and the region files. */
class AtlasTilesTest {
	@Test
	void cellsEncodeHeightAndWaterAndZeroMeansUnknown() {
		for (int y : new int[] {AtlasTiles.MIN_Y, -64, 0, 1, 67, 455, AtlasTiles.MAX_Y}) {
			for (boolean water : new boolean[] {false, true}) {
				short cell = AtlasTiles.encode(y, water);
				assertTrue(AtlasTiles.known(cell));
				assertEquals(y, AtlasTiles.height(cell));
				assertEquals(water, AtlasTiles.water(cell));
			}
		}

		assertFalse(AtlasTiles.known((short) 0));
		assertEquals(AtlasTiles.MAX_Y, AtlasTiles.height(AtlasTiles.encode(99999, false))); // clamped
		assertEquals(AtlasTiles.MIN_Y, AtlasTiles.height(AtlasTiles.encode(-99999, true)));
	}

	@Test
	void cellsTilesAndRegionsFloorTowardMinusInfinity() {
		assertEquals(-1, AtlasTiles.cellOf(-0.5));
		assertEquals(0, AtlasTiles.cellOf(1.99));
		assertEquals(-623, AtlasTiles.cellOf(-1245.5));
		assertEquals(-1, AtlasTiles.tileOf(-1));
		assertEquals(-10, AtlasTiles.tileOf(-623));
		assertEquals(-2, AtlasTiles.regionOf(-10));
		long k = AtlasTiles.key(-5, 7);
		assertEquals(-5, AtlasTiles.keyA(k));
		assertEquals(7, AtlasTiles.keyB(k));
	}

	@Test
	void putGetAndVersions() {
		AtlasTiles tiles = new AtlasTiles();
		assertEquals(0, tiles.get(-623, -407));
		assertTrue(tiles.put(-623, -407, 67, false));
		assertFalse(tiles.put(-623, -407, 67, false)); // the same value is no change
		assertEquals(67, AtlasTiles.height(tiles.get(-623, -407)));
		int tx = AtlasTiles.tileOf(-623), tz = AtlasTiles.tileOf(-407);
		assertTrue(tiles.hasTile(tx, tz));
		int v = tiles.tileVersion(tx, tz);
		long sum = tiles.versionSum(tx - 1, tz - 1, tx + 1, tz + 1);
		tiles.put(-623, -407, 12, true);
		assertEquals(v + 1, tiles.tileVersion(tx, tz));
		assertNotEquals(sum, tiles.versionSum(tx - 1, tz - 1, tx + 1, tz + 1));
		assertEquals(0L, tiles.versionSum(100, 100, 101, 101));
		int generation = tiles.generation();
		tiles.clear();
		assertEquals(generation + 1, tiles.generation());
		assertEquals(0, tiles.tileCount());
	}

	@Test
	void copyFillsUnknownWithZeroAcrossTileEdges() {
		AtlasTiles tiles = new AtlasTiles();
		tiles.put(-1, -1, 5, false); // the last cell of tile (-1, -1)
		tiles.put(0, 0, 7, true);    // the first cell of tile (0, 0)
		short[] out = new short[4 * 4];
		tiles.copy(-2, -2, 4, out);
		assertEquals(5, AtlasTiles.height(out[1 * 4 + 1]));
		assertTrue(AtlasTiles.water(out[2 * 4 + 2]));
		assertEquals(0, out[0]);
		assertEquals(0, out[3 * 4 + 3]);
	}

	@Test
	void coverageCountsKnownCells() {
		AtlasTiles tiles = new AtlasTiles();
		for (int x = 0; x < 10; x++) {
			for (int z = 0; z < 5; z++) {
				tiles.put(x, z, 1, false);
			}
		}

		assertEquals(1.0, tiles.coverage(0, 0, 9, 4), 1e-9);
		assertEquals(0.5, tiles.coverage(0, 0, 9, 9), 1e-9);
		assertEquals(0.0, tiles.coverage(100, 100, 120, 120), 1e-9);
	}

	@Test
	void regionFilesRoundTripAndOnlyChangedRegionsAreDirty() {
		AtlasTiles tiles = new AtlasTiles();
		tiles.put(-623, -407, 67, false);
		tiles.put(-600, -400, 0, true);
		tiles.put(10, 10, 300, false);
		Map<Long, byte[]> dirty = tiles.takeDirtyRegions();
		assertEquals(2, dirty.size()); // regions (-2, -1) and (0, 0)
		assertTrue(tiles.takeDirtyRegions().isEmpty());

		AtlasTiles loaded = new AtlasTiles();
		for (Map.Entry<Long, byte[]> region : dirty.entrySet()) {
			assertTrue(loaded.decodeRegion(AtlasTiles.keyA(region.getKey()), AtlasTiles.keyB(region.getKey()), region.getValue()));
		}

		assertEquals(tiles.get(-623, -407), loaded.get(-623, -407));
		assertEquals(tiles.get(-600, -400), loaded.get(-600, -400));
		assertEquals(tiles.get(10, 10), loaded.get(10, 10));
		assertEquals(tiles.tileCount(), loaded.tileCount());
		// a region file is small: an almost empty 64 x 64 tile deflates to well under its 8 KB
		assertTrue(dirty.values().stream().allMatch(b -> b.length < 1024));
		assertFalse(loaded.decodeRegion(0, 0, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
		assertFalse(loaded.decodeRegion(0, 0, new byte[0]));
	}

	@Test
	void mergeTakesOnlyCellsTheMapDoesNotKnow() {
		AtlasTiles world = new AtlasTiles();
		world.put(1, 1, 10, false);
		world.put(2, 2, 20, false);
		byte[] file = world.encodeRegion(0, 0);
		AtlasTiles shared = new AtlasTiles();
		shared.put(1, 1, 99, false);
		assertTrue(shared.mergeRegion(0, 0, file));
		assertEquals(99, AtlasTiles.height(shared.get(1, 1))); // kept
		assertEquals(20, AtlasTiles.height(shared.get(2, 2))); // taken
		assertEquals(1, shared.takeDirtyRegions().size());
	}
}
