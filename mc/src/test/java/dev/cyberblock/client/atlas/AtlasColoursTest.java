package dev.cyberblock.client.atlas;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;

/** The Atlas's colour mapping: height bands over the local ground, vanilla relief, water, and the ground search. */
class AtlasColoursTest {
	@Test
	void bandsGetDarkerWithHeightAndWaterIsWater() {
		assertSame(MapColor.SAND, AtlasColours.band(0, false));
		assertSame(MapColor.SAND, AtlasColours.band(2, false));
		assertSame(MapColor.COLOR_LIGHT_GRAY, AtlasColours.band(3, false));
		assertSame(MapColor.STONE, AtlasColours.band(12, false));
		assertSame(MapColor.COLOR_GRAY, AtlasColours.band(40, false));
		assertSame(MapColor.COLOR_BLACK, AtlasColours.band(100, false));
		assertSame(MapColor.WATER, AtlasColours.band(0, true));
		assertSame(MapColor.WATER, AtlasColours.band(80, true));
		// every band is darker than the one below it
		int last = Integer.MAX_VALUE;
		for (int above : new int[] {0, 5, 20, 60, 150}) {
			int c = AtlasColours.band(above, false).col;
			int lum = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
			assertTrue(lum < last, "band at " + above + " m");
			last = lum;
		}
	}

	@Test
	void reliefIsVanillaStyle() {
		assertSame(MapColor.Brightness.HIGH, AtlasColours.relief(10, 5, true));
		assertSame(MapColor.Brightness.NORMAL, AtlasColours.relief(10, 10, true));
		assertSame(MapColor.Brightness.LOW, AtlasColours.relief(10, 12, true));
		assertSame(MapColor.Brightness.LOWEST, AtlasColours.relief(10, 10 + AtlasColours.SHADOW_DROP, true));
		assertSame(MapColor.Brightness.NORMAL, AtlasColours.relief(10, 99, false));
	}

	@Test
	void slidingMinMatchesTheNaiveWindow() {
		int[] in = {5, 3, 9, Integer.MAX_VALUE, 1, 7, 8, 2, 6};
		int[] out = new int[in.length];
		AtlasColours.slidingMin(in, 2, out);
		for (int i = 0; i < in.length; i++) {
			int min = Integer.MAX_VALUE;
			for (int j = Math.max(0, i - 2); j <= Math.min(in.length - 1, i + 2); j++) {
				min = Math.min(min, in[j]);
			}

			assertEquals(min, out[i], "at " + i);
		}
	}

	@Test
	void aTowerOnAStreetBesideTheSea() {
		// 20 x 20 cells with a margin of 4: street at y 10, a 60 m tower in the middle, sea (water at y 0) on the east, unknown north-west
		int size = 12, margin = 4, full = size + 2 * margin;
		short[] cells = new short[full * full];
		for (int z = 0; z < full; z++) {
			for (int x = 0; x < full; x++) {
				boolean sea = x >= 15;
				boolean tower = x >= 8 && x < 12 && z >= 8 && z < 12;
				boolean unknown = x < 6 && z < 6;
				cells[z * full + x] = unknown ? 0 : AtlasTiles.encode(sea ? 0 : tower ? 70 : 10, sea);
			}
		}

		int[] px = AtlasColours.shade(cells, size, margin, margin);
		assertEquals(0, px[0]); // unknown stays transparent: the parchment shows
		int street = px[(10 - margin) * size + (14 - margin)];
		assertEquals(MapColor.SAND.calculateARGBColor(MapColor.Brightness.NORMAL), street);
		// the tower's north edge is brighter (higher than its north neighbour), its inside normal, both tall-building grey
		assertEquals(MapColor.COLOR_GRAY.calculateARGBColor(MapColor.Brightness.HIGH), px[(8 - margin) * size + (9 - margin)]);
		assertEquals(MapColor.COLOR_GRAY.calculateARGBColor(MapColor.Brightness.NORMAL), px[(10 - margin) * size + (9 - margin)]);
		// the street south of it lies in its shadow (a 60 m drop)
		assertEquals(MapColor.SAND.calculateARGBColor(MapColor.Brightness.LOWEST), px[(12 - margin) * size + (9 - margin)]);
		// the sea is water, and the water's low surface does not count as ground (the street stays street next to it)
		assertEquals(MapColor.WATER.calculateARGBColor(MapColor.Brightness.NORMAL), px[(10 - margin) * size + (15 - margin)]);
		assertEquals(street, px[(10 - margin) * size + (14 - margin)]);
	}
}
