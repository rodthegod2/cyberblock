package dev.cyberblock.figure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: the look the server syncs, and the log lines that say which data and which Phase 0 (f) path is in use. */
class FiguresTest {
	@Test
	void thePersonsLabelIsRoleThenFaction() {
		SkinMap.Skin skin = new SkinMap.Skin("x", true);
		assertEquals("role:RipperDoc", FigureLook.person(skin, new FigureTags("RipperDoc", "NCPD", 2, "", 0, "", -1, 1.0F)).label());
		assertEquals("faction:NCPD", FigureLook.person(skin, new FigureTags("", "NCPD", 2, "", 0, "", -1, 1.0F)).label());
		assertEquals("", FigureLook.person(skin, new FigureTags("", "Civilian", 2, "", 0, "", -1, 1.0F)).label());
		assertEquals("", FigureLook.person(skin, FigureTags.NONE).label());
		FigureLook look = FigureLook.person(skin, new FigureTags("", "Mox", 2, "", 12, "", 0x112233, 0.4F));
		assertEquals(new FigureLook("x", true, 12, -1, 0.4F, "faction:Mox"), look, "a person has no paint");
	}

	@Test
	void theVehiclesLookIsItsModelAndPaint() {
		FigureLook look = FigureLook.vehicle(new VehicleMap.Vehicle(VehicleModel.TAXI, 0xF2C230), new FigureTags("", "", 0, "", 0, "Car/Thorton", -1, 0.7F));
		assertEquals(new FigureLook("taxi", false, 0, 0xF2C230, 0.7F, "vehicle:taxi"), look);
	}

	@Test
	void threatWordsAreLevels() {
		assertEquals(0, FigureLook.threat("none"));
		assertEquals(4, FigureLook.threat("extreme"));
		assertEquals(3, FigureLook.threat("High"));
		assertEquals(-1, FigureLook.threat("deadly"));
	}

	@Test
	void theRowsAreCountedOncePerStart() {
		assertEquals("figures: skin_map 31 rows, vehicle_map 12 rows", Figures.rowsLine(31, 12));
	}

	@Test
	void theTagsLineNamesThePathInUse() {
		assertEquals("figures: CbHost1 sends no npcs tags (fallback: neutral skins, walk and run from motion, class default colours, full hp)",
			Figures.tagsLine("CbHost1", Set.of()));
		assertEquals("figures: CbHost1 npcs tags anim arch col fac g hp role vc (all)",
			Figures.tagsLine("CbHost1", new java.util.TreeSet<>(Set.of(FigureTags.FIELDS))));
		assertEquals("figures: CbHost1 npcs tags anim arch fac g hp vc; missing role col (fallback: skins by faction (no role), class default colours)",
			Figures.tagsLine("CbHost1", new java.util.TreeSet<>(Set.of("anim", "arch", "fac", "g", "hp", "vc"))));
	}

	@Test
	void vehiclesWithoutAFactionAreNamedInTheTagsLine() {
		java.util.TreeSet<String> seen = new java.util.TreeSet<>(Set.of(FigureTags.FIELDS));
		seen.add(FigureTags.VEHICLES);
		assertEquals("figures: CbHost1 npcs tags anim arch col fac g hp role vc (all); vehicles: fac missing (fallback: no police or Trauma liveries)",
			Figures.tagsLine("CbHost1", seen));
		seen.add(FigureTags.VEHICLE_FAC);
		assertEquals("figures: CbHost1 npcs tags anim arch col fac g hp role vc (all); vehicles: fac sent", Figures.tagsLine("CbHost1", seen));
	}

	@Test
	void aVehicleEntryWithAnEmptyFacDoesNotCountAsSendingIt() {
		com.google.gson.JsonObject message = com.google.gson.JsonParser.parseString(
			"{\"t\":\"npcs\",\"list\":[{\"id\":\"1\",\"kind\":\"human\",\"fac\":\"NCPD\"},{\"id\":\"2\",\"kind\":\"vehicle\",\"fac\":\"\",\"vc\":\"Car/Cortes\"}]}")
			.getAsJsonObject();
		Set<String> seen = FigureTags.parse(message).seen();
		assertTrue(seen.contains("fac") && seen.contains(FigureTags.VEHICLES));
		assertFalse(seen.contains(FigureTags.VEHICLE_FAC), "people sent fac, the vehicle did not");
	}
}
