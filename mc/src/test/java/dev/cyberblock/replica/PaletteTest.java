package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Ranges;
import dev.cyberblock.replica.Palette.Heuristic;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Megaplan 3-R1: probe codes through material_palette, and the heuristic alone (every m 0 or absent, no content at all). */
class PaletteTest {
	private static final int NONE = Integer.MIN_VALUE;

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s.replace('\'', '"')).getAsJsonObject();
	}

	private static Identifier id(final String name) {
		return Identifier.fromNamespaceAndPath("cyberblock", name);
	}

	@AfterEach
	void builtIn() {
		Palette.use(Map.of());
	}

	@Test
	void withoutContentEveryProbeCodeIsItsOwnBlock() {
		Palette.use(Map.of());
		for (int code = 1; code < ReplicaBlocks.CODES; code++) {
			assertEquals(code, Palette.fromProbe(code));
		}

		assertEquals(0, Palette.fromProbe(0));
		assertEquals(0, Palette.fromProbe(27));
		assertEquals(0, Palette.fromProbe(-3));
	}

	@Test
	void theHeuristicAloneGivesEveryCellABlock() {
		Palette.use(Map.of());
		Ranges.Range street = new Ranges.Range(10, 4, 63, 63), tower = new Ranges.Range(10, 5, 64, 90);
		for (int y = 63; y <= 90; y++) {
			Ranges.Range r = y == 63 ? street : tower;
			int m = Palette.material(0, r, y, 64, NONE, false, "");
			assertTrue(m >= 1 && m < ReplicaBlocks.CODES, "cell y " + y + " got " + m);
		}
	}

	@Test
	void floorsByHeightAgainstTheHostsStreet() {
		Ranges.Range floor = new Ranges.Range(0, 0, 63, 63);
		assertEquals(Heuristic.STREET, Palette.classify(floor, 63, 64, NONE, false, ""), "the floor Steve stands on");
		assertEquals(Heuristic.STREET, Palette.classify(new Ranges.Range(0, 0, 40, 40), 40, 64, NONE, false, ""), "a road below");
		assertEquals(Heuristic.SIDEWALK, Palette.classify(new Ranges.Range(0, 0, 65, 65), 65, 64, NONE, false, ""), "a step up");
		assertEquals(Heuristic.ROOF, Palette.classify(new Ranges.Range(0, 0, 80, 81), 81, 64, NONE, false, ""), "high up");
		assertEquals(Heuristic.ROOF, Palette.classify(new Ranges.Range(0, 0, 65, 65), 65, 64, 65, false, ""), "the map's highest surface");
		assertEquals(Heuristic.OFFROAD, Palette.classify(floor, 63, 64, NONE, false, "Badlands"));
		assertEquals(Heuristic.OFFROAD, Palette.classify(floor, 63, 64, NONE, false, "Rocky Ridge"));
	}

	@Test
	void waterFromTheSharedMap() {
		Ranges.Range floor = new Ranges.Range(0, 0, 50, 50);
		assertEquals(Heuristic.WATER, Palette.classify(floor, 50, 64, 50, true, ""));
		assertEquals(Heuristic.STREET, Palette.classify(floor, 50, 64, 50, false, ""));
		assertEquals(Heuristic.STREET, Palette.classify(floor, 50, 64, NONE, true, ""), "no map height: no water guess");
	}

	@Test
	void wallsHaveABaseFloorLinesWindowsAndARoof() {
		Ranges.Range wall = new Ranges.Range(1, 0, 64, 80);
		assertEquals(Heuristic.WALL_BASE, Palette.classify(wall, 64, 64, NONE, false, ""));
		assertEquals(Heuristic.WALL_BASE, Palette.classify(wall, 66, 64, NONE, false, ""));
		assertEquals(Heuristic.WALL_BRICK, Palette.classify(wall, 64, 64, NONE, false, "Watson"));
		assertEquals(Heuristic.WALL_BRICK, Palette.classify(wall, 65, 64, NONE, false, "Little China"));
		assertEquals(Heuristic.WALL_TRIM, Palette.classify(wall, 68, 64, NONE, false, ""));
		assertEquals(Heuristic.WALL_WINDOW, Palette.classify(wall, 69, 64, NONE, false, ""), "x + z = 1: a window column");
		assertEquals(Heuristic.WALL, Palette.classify(new Ranges.Range(3, 0, 64, 80), 69, 64, NONE, false, ""), "x + z = 3: a pillar");
		assertEquals(Heuristic.WALL, Palette.classify(wall, 71, 64, NONE, false, ""));
		assertEquals(Heuristic.ROOF, Palette.classify(wall, 80, 64, NONE, false, ""));
	}

	@Test
	void eachHeuristicClassHasItsDefaultBlock() {
		Palette.use(Map.of());
		assertEquals(ReplicaBlocks.ASPHALT, Palette.fromHeuristic(Heuristic.STREET));
		assertEquals(ReplicaBlocks.SIDEWALK, Palette.fromHeuristic(Heuristic.SIDEWALK));
		assertEquals(ReplicaBlocks.GLASS, Palette.fromHeuristic(Heuristic.WALL_WINDOW));
		assertEquals(ReplicaBlocks.WATER, Palette.fromHeuristic(Heuristic.WATER));
		assertEquals(ReplicaBlocks.GROUND, Palette.fromHeuristic(Heuristic.OFFROAD));
	}

	@Test
	void aProbeCodeWinsOverTheHeuristic() {
		Palette.use(Map.of());
		Ranges.Range floor = new Ranges.Range(0, 0, 63, 63);
		assertEquals(ReplicaBlocks.TILE, Palette.material(ReplicaBlocks.TILE, floor, 63, 64, NONE, false, ""));
		assertEquals(ReplicaBlocks.ASPHALT, Palette.material(0, floor, 63, 64, NONE, false, ""));
		assertEquals(ReplicaBlocks.NEON_FIRST + 3, Palette.material(ReplicaBlocks.NEON_FIRST + 3, floor, 63, 64, NONE, false, ""), "light blue neon");
	}

	@Test
	void contentEntriesOverrideTheBuiltInTable() {
		Palette.use(Map.of(
			id("probe_tile_as_sidewalk"), Palette.parse(id("a"), json("{'schema':1,'code':8,'block':'cyberblock:nc_sidewalk'}")),
			id("streets_are_brick"), Palette.parse(id("b"), json("{'schema':1,'heuristic':'street','block':'cyberblock:nc_brick','colour':'#884422'}")),
			id("plain"), Palette.parse(id("c"), json("{'schema':1,'block':'cyberblock:nc_metal','names':['steel'],'colour':'#AABBCC'}"))));
		assertEquals(ReplicaBlocks.SIDEWALK, Palette.fromProbe(ReplicaBlocks.TILE));
		assertEquals(ReplicaBlocks.BRICK, Palette.fromHeuristic(Heuristic.STREET));
		assertEquals(ReplicaBlocks.METAL, Palette.fromProbe(ReplicaBlocks.METAL));
		assertEquals(ReplicaBlocks.ASPHALT, Palette.fromProbe(ReplicaBlocks.ASPHALT), "untouched codes keep their block");
	}

	@Test
	void badEntriesAreRefusedWithAReason() {
		for (String bad : new String[] {
			"{'schema':1}",
			"{'schema':1,'block':'minecraft:stone'}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','code':0}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','code':27}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','code':2.5}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','heuristic':'plaza'}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','heuristic':'street','code':3}",
			"{'schema':1,'block':'cyberblock:nc_asphalt','colour':'red'}"}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Palette.parse(id("x"), json(bad)), bad);
			assertNotNull(e.getMessage());
		}
	}
}
