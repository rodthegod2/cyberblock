package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Megaplan Q-V4 / section 1.9: the prefs message always carries six valid fields and the view debug flag. */
class ViewPrefsTest {
	@Test
	void defaultsAreTheDllsAndTheMessageHasAllSixFields() {
		JsonObject m = ViewPrefs.DEFAULT.message(false);
		assertEquals(JsonParser.parseString("{'t':'prefs','dist':3.2,'shoulder':0.45,'fov':0.0,'recenter':true,'vdist':0.0,'stepup':true,'debug':false}"), m);
	}

	@Test
	void theViewDebugFlagRidesOnTheMessageButNeverInTheFile() {
		assertTrue(ViewPrefs.DEFAULT.message(true).get("debug").getAsBoolean());
		assertFalse(ViewPrefs.DEFAULT.message(false).get("debug").getAsBoolean(), "off is sent too, so the DLL hears it go off");
		assertFalse(ViewPrefs.DEFAULT.toJson().has("debug"));
		assertEquals(ViewPrefs.DEFAULT, ViewPrefs.fromJson(JsonParser.parseString("{'debug':true}").getAsJsonObject()));
	}

	@Test
	void numbersAreClampedIntoTheirRanges() {
		ViewPrefs p = new ViewPrefs(20.0, -3.0, 200.0, false, 1.0, false);
		assertEquals(8.0, p.dist());
		assertEquals(-1.0, p.shoulder());
		assertEquals(110.0, p.fov());
		assertEquals(3.0, p.vdist(), "above 0 but under the range is raised to it");
		ViewPrefs low = new ViewPrefs(0.1, 0.0, 10.0, true, 99.0, true);
		assertEquals(1.5, low.dist());
		assertEquals(50.0, low.fov());
		assertEquals(14.0, low.vdist());
	}

	@Test
	void zeroOrBadMeansTheGamesOwnFovAndVehicleDistance() {
		ViewPrefs p = new ViewPrefs(Double.NaN, Double.POSITIVE_INFINITY, -5.0, true, Double.NaN, true);
		assertEquals(3.2, p.dist());
		assertEquals(0.45, p.shoulder());
		assertEquals(0.0, p.fov());
		assertEquals(0.0, p.vdist());
	}

	@Test
	void theFileRoundTripsAndBadEntriesKeepTheirDefaults() {
		ViewPrefs p = new ViewPrefs(5.25, -0.3, 90.0, false, 8.0, false);
		assertEquals(p, ViewPrefs.fromJson(p.toJson()));
		ViewPrefs bad = ViewPrefs.fromJson(JsonParser.parseString("{'dist':'far','shoulder':null,'fov':75,'recenter':1,'stepup':false}").getAsJsonObject());
		assertEquals(new ViewPrefs(3.2, 0.45, 75.0, true, 0.0, false), bad);
	}

	@Test
	void sliderNoiseIsRoundedOnTheWire() {
		JsonObject m = ViewPrefs.DEFAULT.withDist(3.2000000000000006).withShoulder(0.449999).message(false);
		assertEquals(3.2, m.get("dist").getAsDouble());
		assertEquals(0.45, m.get("shoulder").getAsDouble());
	}
}
