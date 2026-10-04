package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.atlas.AtlasTiles;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Megaplan 3-R2: the shared Night City map in the Atlas region format, terrain validation, and the maptile answer. */
class NightCityMapTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s.replace('\'', '"')).getAsJsonObject();
	}

	@Test
	void cellsAreKeptPerTile() {
		NightCityMap.Tiles t = new NightCityMap.Tiles();
		assertTrue(t.put(-623, -407, 67, false));
		assertFalse(t.put(-623, -407, 67, false), "no change");
		assertTrue(t.put(-623, -407, 68, true));
		assertEquals(68, NightCityMap.Tiles.height(t.get(-623, -407)));
		assertEquals(1, t.get(-623, -407) & 1);
		assertEquals(0, t.get(0, 0));
		assertEquals(1, t.size());
	}

	@Test
	void theRegionFormatIsTheClientAtlasOne() {
		NightCityMap.Tiles t = new NightCityMap.Tiles();
		t.put(-623, -407, 67, false);
		t.put(-700, -380, 0, true);
		t.put(5, 6, -5000, false); // clamped like the Atlas
		Map<Long, byte[]> regions = t.takeDirty();
		assertTrue(t.takeDirty().isEmpty(), "clean after a save");
		AtlasTiles atlas = new AtlasTiles();
		for (Map.Entry<Long, byte[]> e : regions.entrySet()) {
			assertTrue(atlas.decodeRegion(AtlasTiles.keyA(e.getKey()), AtlasTiles.keyB(e.getKey()), e.getValue()));
		}

		assertEquals(67, AtlasTiles.height(atlas.get(-623, -407)));
		assertFalse(AtlasTiles.water(atlas.get(-623, -407)));
		assertTrue(AtlasTiles.water(atlas.get(-700, -380)));
		assertEquals(AtlasTiles.MIN_Y, AtlasTiles.height(atlas.get(5, 6)));
		// and back: a region the client wrote loads on the server
		AtlasTiles client = new AtlasTiles();
		client.put(100, 200, 42, true);
		NightCityMap.Tiles server = new NightCityMap.Tiles();
		int tx = AtlasTiles.tileOf(100), tz = AtlasTiles.tileOf(200);
		assertTrue(server.decodeRegion(AtlasTiles.regionOf(tx), AtlasTiles.regionOf(tz), client.encodeRegion(AtlasTiles.regionOf(tx), AtlasTiles.regionOf(tz))));
		assertEquals(42, NightCityMap.Tiles.height(server.get(100, 200)));
		assertFalse(server.decodeRegion(0, 0, new byte[] {1, 2, 3}), "not a region file");
	}

	@Test
	void theMapIsSavedWithTheWorldAndReadBack(@TempDir final Path dir) throws Exception {
		NightCityMap.open(dir);
		NightCityMap.tiles().put(10, 20, 70, false);
		NightCityMap.detach();
		assertTrue(Files.exists(dir.resolve("r.0.0.cbat")));
		NightCityMap.open(dir);
		assertEquals(70, NightCityMap.top(20, 41), "block (20, 41) is cell (10, 20)");
		assertEquals(Integer.MIN_VALUE, NightCityMap.top(0, 0));
		NightCityMap.detach();
	}

	@Test
	void terrainMessagesAreValidated() {
		assertArrayEquals(new int[] {-623, -407, 67, 0, -700, -380, 0, 1},
			NightCityMap.cells(json("{'t':'terrain','c':[-623,-407,67,0,-700,-380,0,1]}")));
		assertNull(NightCityMap.cells(json("{'c':[1,2,3]}")), "not whole quadruples");
		assertNull(NightCityMap.cells(json("{'c':[1,2,3,2]}")), "s is 0 or 1");
		assertNull(NightCityMap.cells(json("{'c':[1,2,30000,0]}")), "y out of range");
		assertNull(NightCityMap.cells(json("{'c':[1.5,2,3,0]}")));
		assertNull(NightCityMap.cells(json("{'c':[2e7,2,3,0]}")));
		assertNull(NightCityMap.cells(json("{'c':'x'}")));
		assertNull(NightCityMap.cells(json("{}")));
	}

	@Test
	void tileRequestsAreValidated() {
		assertArrayEquals(new long[] {1, -2, 0, 3, 4, 77}, NightCityMap.parseRequest(json("{'tiles':[1,-2,3,4],'have':[0,77]}")));
		assertArrayEquals(new long[] {1, -2, 0}, NightCityMap.parseRequest(json("{'tiles':[1,-2]}")));
		assertNull(NightCityMap.parseRequest(json("{'tiles':[1]}")));
		assertNull(NightCityMap.parseRequest(json("{'tiles':[1,2],'have':[1,2]}")), "have is parallel to the tiles");
		assertNull(NightCityMap.parseRequest(json("{'tiles':[1.5,2]}")));
		assertNull(NightCityMap.parseRequest(json("{'tiles':[1,2],'have':[-1]}")));
		assertNull(NightCityMap.parseRequest(json("{}")));
		StringBuilder many = new StringBuilder("{'tiles':[0,0");
		for (int i = 1; i <= NightCityMap.MAX_REQUEST; i++) {
			many.append(",0,0");
		}

		assertNull(NightCityMap.parseRequest(json(many + "]}")), "too many tiles in one request");
	}

	@Test
	void aTileAnswerCarriesItsCellsAndVersion(@TempDir final Path dir) {
		NightCityMap.open(dir);
		try {
			JsonObject unknown = json(NightCityMap.answer(5, 5, 1)); // a client that thought it held something there
			assertEquals(0, unknown.get("v").getAsLong(), "the server knows nothing there");
			assertFalse(unknown.has("c"));
			assertNull(NightCityMap.answer(5, 5, 0), "the client already knows it knows nothing");

			NightCityMap.tiles().put(64 + 3, -64 + 2, 70, true); // tile (1, -1), cell (3, 2) in it
			JsonObject tile = json(NightCityMap.answer(1, -1, 0));
			assertEquals(1, tile.get("tx").getAsInt());
			assertEquals(-1, tile.get("tz").getAsInt());
			long v = tile.get("v").getAsLong();
			assertTrue(v > 0);
			short[] cells = new short[NightCityMap.TILE * NightCityMap.TILE];
			ByteBuffer.wrap(Base64.getDecoder().decode(tile.get("c").getAsString())).asShortBuffer().get(cells);
			assertEquals(AtlasTiles.encode(70, true), cells[2 * NightCityMap.TILE + 3]);
			assertNull(NightCityMap.answer(1, -1, v), "the client holds this version");
			NightCityMap.tiles().put(64 + 4, -64 + 2, 71, false);
			assertNotNull(NightCityMap.answer(1, -1, v), "the tile changed: a new version");
		} finally {
			NightCityMap.detach();
		}
	}
}
