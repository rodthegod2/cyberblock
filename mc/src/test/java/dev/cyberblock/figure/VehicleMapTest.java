package dev.cyberblock.figure;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Megaplan task 5-F1: the server picks each vehicle's model and paint from vehicle_map in 5A's row format; a livery keeps its colour;
 * with no data the class default applies.
 */
class VehicleMapTest {
	private static VehicleMap.Row row(final String json) {
		return VehicleMap.parse(Identifier.fromNamespaceAndPath("cyberblock", "test"), JsonParser.parseString(json).getAsJsonObject());
	}

	private static FigureTags tags(final String vc, final String fac, final int col) {
		return new FigureTags("", fac, 0, "", 0, vc, col, 1.0F);
	}

	/** 5A's shipped rows, in id order. */
	private static final List<VehicleMap.Row> ROWS = List.of(
		row("{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#8a8f99\",\"vc\":[\"Car/Emperor\",\"Car/Galena\"],\"priority\":10}"),
		row("{\"schema\":1,\"model\":\"sports_car\",\"colour\":\"#c23b3b\",\"vc\":[\"Car/Turbo\",\"Car/Type66\"],\"priority\":10}"),
		row("{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#8a8f99\"}"),
		row("{\"schema\":1,\"model\":\"armoured_van\",\"colour\":\"#3b4136\",\"type\":\"Panzer\",\"faction\":\"Militech\",\"keepColour\":true,\"priority\":20}"),
		row("{\"schema\":1,\"model\":\"police_cruiser\",\"colour\":\"#1f2c4a\",\"type\":\"Car\",\"faction\":\"NCPD\",\"keepColour\":true,\"priority\":20}"),
		row("{\"schema\":1,\"model\":\"trauma_aerodyne\",\"colour\":\"#e8ecef\",\"faction\":\"TraumaTeam\",\"keepColour\":true,\"priority\":20}"),
		row("{\"schema\":1,\"model\":\"bike\",\"colour\":\"#3a3a44\",\"type\":\"Bike\"}"),
		row("{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#8a8f99\",\"type\":\"Car\"}"),
		row("{\"schema\":1,\"model\":\"armoured_van\",\"colour\":\"#3b4136\",\"type\":\"Panzer\"}"));

	@Test
	void priorityThenKeysPickTheModel() {
		assertEquals(VehicleModel.SPORTS_CAR, VehicleMap.resolve(ROWS, tags("Car/Turbo", "", -1)).model());
		assertEquals(VehicleModel.SPORTS_CAR, VehicleMap.resolve(ROWS, tags("car/type 66", "", -1)).model(), "names match without case or spaces");
		assertEquals(VehicleModel.POLICE_CRUISER, VehicleMap.resolve(ROWS, tags("Car/Turbo", "NCPD", -1)).model(), "a livery beats the model row");
		assertEquals(VehicleModel.ARMOURED_VAN, VehicleMap.resolve(ROWS, tags("Panzer/Basilisk", "", -1)).model());
		assertEquals(VehicleModel.BIKE, VehicleMap.resolve(ROWS, tags("Bike/Arch", "", -1)).model());
		assertEquals(VehicleModel.TRAUMA_AERODYNE, VehicleMap.resolve(ROWS, tags("AV/Zeta", "TraumaTeam", -1)).model());
		assertEquals(VehicleModel.SEDAN, VehicleMap.resolve(ROWS, tags("Car/Unheard", "", -1)).model(), "the type row");
		assertEquals(VehicleModel.SEDAN, VehicleMap.resolve(ROWS, FigureTags.NONE).model(), "the default row");
	}

	@Test
	void theHostsColourWinsUnlessTheRowIsALivery() {
		assertEquals(0x123456, VehicleMap.resolve(ROWS, tags("Car/Turbo", "", 0x123456)).colour());
		assertEquals(0xC23B3B, VehicleMap.resolve(ROWS, tags("Car/Turbo", "", -1)).colour(), "no col: the row's colour");
		assertEquals(0x1F2C4A, VehicleMap.resolve(ROWS, tags("Car/Turbo", "NCPD", 0x123456)).colour(), "a police car keeps its livery");
	}

	@Test
	void withoutDataTheClassDefaultApplies() {
		assertEquals(new VehicleMap.Vehicle(VehicleModel.SEDAN, VehicleModel.SEDAN.defaultColour()), VehicleMap.resolve(List.of(), FigureTags.NONE));
		assertEquals(VehicleModel.BIKE, VehicleMap.resolve(List.of(), tags("Bike/Arch", "", -1)).model());
		assertEquals(VehicleModel.POLICE_CRUISER, VehicleMap.resolve(List.of(), tags("Car/Hella", "NCPD", -1)).model());
		assertEquals(VehicleModel.TRAUMA_AERODYNE, VehicleMap.resolve(List.of(), tags("", "TraumaTeam", -1)).model());
		assertEquals(0x123456, VehicleMap.resolve(List.of(), tags("Car/Hella", "", 0x123456)).colour());
	}

	@Test
	void badRowsAreRefused() {
		String[] bad = {"{\"schema\":1,\"model\":\"hovercraft\",\"colour\":\"#000000\"}", "{\"schema\":1,\"model\":\"sedan\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#000000\",\"vc\":[]}", "{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#000000\",\"vc\":\"Car\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"colour\":\"red\"}", "{\"schema\":1,\"colour\":\"#000000\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#000000\",\"keepColour\":\"yes\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"colour\":\"#000000\",\"priority\":1.5}"};
		for (String b : bad) {
			assertThrows(IllegalArgumentException.class, () -> row(b), b);
		}
	}

	@Test
	void everyModelHasItsIdAndAColour() {
		List<String> ids = List.of("sedan", "sports_car", "truck", "van", "bike", "taxi", "police_cruiser", "armoured_van", "trauma_aerodyne",
			"ncpd_aerodyne", "delivery_truck", "pickup");
		for (String id : ids) {
			VehicleModel m = VehicleModel.byId(id);
			assertNotNull(m, id + " (5A's model ids)");
			assertTrue(m.defaultColour() >= 0 && m.defaultColour() <= 0xFFFFFF);
		}

		assertNull(VehicleModel.byId("hovercraft"));
		assertTrue(VehicleModel.POLICE_CRUISER.siren() && VehicleModel.NCPD_AERODYNE.siren() && VehicleModel.TRAUMA_AERODYNE.siren());
		assertFalse(VehicleModel.TAXI.siren());
	}
}
