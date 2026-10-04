package dev.cyberblock.figure;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: the server picks each vehicle's model and paint from vehicle_map; no colour means the class default. */
class VehicleMapTest {
	private static VehicleMap.Row row(final String json) {
		return VehicleMap.parse(Identifier.fromNamespaceAndPath("cyberblock", "test"), JsonParser.parseString(json).getAsJsonObject());
	}

	private static FigureTags tags(final String vc, final String fac, final int col) {
		return new FigureTags("", fac, 0, "", 0, vc, col, 1.0F);
	}

	private static final List<VehicleMap.Row> ROWS = List.of(
		row("{\"schema\":1,\"model\":\"sports\",\"class\":[\"Car/Turbo\",\"Car/Type 66\"],\"colour\":\"#AA0000\"}"),
		row("{\"schema\":1,\"model\":\"sedan\",\"class\":\"Car\"}"),
		row("{\"schema\":1,\"model\":\"police\",\"class\":\"Car\",\"faction\":\"NCPD\",\"colour\":\"#101820\"}"),
		row("{\"schema\":1,\"model\":\"bike\",\"class\":\"Bike\"}"),
		row("{\"schema\":1,\"model\":\"ncpd_av\",\"faction\":\"NCPD\",\"class\":\"AV\"}"));

	@Test
	void theMostSpecificRowWins() {
		assertEquals(new VehicleMap.Vehicle(VehicleModel.SPORTS, 0xAA0000), VehicleMap.resolve(ROWS, tags("Car/Turbo", "", -1)));
		assertEquals(new VehicleMap.Vehicle(VehicleModel.SPORTS, 0xAA0000), VehicleMap.resolve(ROWS, tags("car/type66", "", -1)));
		assertEquals(new VehicleMap.Vehicle(VehicleModel.POLICE, 0x101820), VehicleMap.resolve(ROWS, tags("Car/Hella", "NCPD", -1)),
			"type and faction beat the bare type");
		assertEquals(VehicleModel.SEDAN, VehicleMap.resolve(ROWS, tags("Car/Hella", "", -1)).model(), "a bare type covers its models");
		assertEquals(VehicleModel.BIKE, VehicleMap.resolve(ROWS, tags("Bike/Arch", "", -1)).model());
		assertEquals(VehicleModel.NCPD_AV, VehicleMap.resolve(ROWS, tags("AV/Zeta", "NCPD", -1)).model());
	}

	@Test
	void theHostsColourWinsThenTheRowsThenTheModels() {
		assertEquals(0x123456, VehicleMap.resolve(ROWS, tags("Car/Turbo", "", 0x123456)).colour());
		assertEquals(0xAA0000, VehicleMap.resolve(ROWS, tags("Car/Turbo", "", -1)).colour());
		assertEquals(VehicleModel.SEDAN.defaultColour(), VehicleMap.resolve(ROWS, tags("Car/Hella", "", -1)).colour());
	}

	@Test
	void withoutDataTheClassDefaultApplies() {
		assertEquals(new VehicleMap.Vehicle(VehicleModel.SEDAN, VehicleModel.SEDAN.defaultColour()), VehicleMap.resolve(List.of(), FigureTags.NONE));
		assertEquals(VehicleModel.BIKE, VehicleMap.resolve(List.of(), tags("Bike/Arch", "", -1)).model());
		assertEquals(VehicleModel.POLICE, VehicleMap.resolve(List.of(), tags("Car/Hella", "NCPD", -1)).model());
		assertEquals(VehicleModel.TRAUMA_AV, VehicleMap.resolve(List.of(), tags("", "TraumaTeam", -1)).model());
		assertEquals(VehicleModel.SEDAN, VehicleMap.resolve(ROWS, tags("Panzer/Basilisk", "", -1)).model(), "no row for the type");
	}

	@Test
	void badRowsAreRefused() {
		String[] bad = {"{\"schema\":1,\"model\":\"hovercraft\",\"class\":\"Car\"}", "{\"schema\":1,\"model\":\"sedan\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"class\":[]}", "{\"schema\":1,\"model\":\"sedan\",\"class\":\"//\"}",
			"{\"schema\":1,\"model\":\"sedan\",\"class\":\"Car\",\"colour\":\"red\"}", "{\"schema\":1,\"class\":\"Car\"}"};
		for (String b : bad) {
			assertThrows(IllegalArgumentException.class, () -> row(b), b);
		}
	}

	@Test
	void everyModelHasAnIdAndAColour() {
		for (VehicleModel m : VehicleModel.values()) {
			assertEquals(m, VehicleModel.byId(m.id()));
			assertTrue(m.defaultColour() >= 0 && m.defaultColour() <= 0xFFFFFF);
		}

		assertNull(VehicleModel.byId("hovercraft"));
		assertTrue(VehicleModel.POLICE.siren() && VehicleModel.NCPD_AV.siren() && !VehicleModel.TAXI.siren());
	}
}
