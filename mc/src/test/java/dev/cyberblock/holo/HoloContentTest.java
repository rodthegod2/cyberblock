package dev.cyberblock.holo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan 7-T1: the landmark content entries and the built-in density layers. */
class HoloContentTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "afterlife");

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void aLandmarkWithDefaults() {
		Landmarks.Landmark l = Landmarks.parse(ID, json("{\"schema\":1,\"name\":\"Afterlife\",\"pos\":[10,64,-20]}"));
		assertEquals("Afterlife", l.name());
		assertEquals(new Vec3(10, 64, -20), l.pos());
		assertEquals("", l.district());
		assertEquals("landmark", l.kind());
		assertTrue(l.spawn());
	}

	@Test
	void aFullLandmark() {
		Landmarks.Landmark l = Landmarks.parse(ID, json(
			"{\"schema\":1,\"name\":\"Afterlife\",\"pos\":[10,64,-20],\"district\":\"Watson\",\"kind\":\"bar\",\"yaw\":90,\"spawn\":false,\"extra\":1}"));
		assertEquals("Watson", l.district());
		assertEquals("bar", l.kind());
		assertEquals(90.0F, l.yaw());
		assertFalse(l.spawn());
	}

	@Test
	void badLandmarksAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> Landmarks.parse(ID, json("{\"schema\":1,\"pos\":[0,0,0]}")));
		assertThrows(IllegalArgumentException.class, () -> Landmarks.parse(ID, json("{\"schema\":1,\"name\":\"a\",\"pos\":[0,0]}")));
		assertThrows(IllegalArgumentException.class, () -> Landmarks.parse(ID, json("{\"schema\":1,\"name\":\"a\",\"pos\":[0,0,0],\"spawn\":\"yes\"}")));
		assertThrows(IllegalArgumentException.class, () -> Landmarks.parse(ID, json("{\"schema\":1,\"name\":\"a\",\"pos\":[0,0,0],\"yaw\":\"east\"}")));
	}

	/** Another package's landmark record (7N might register the kind first). */
	record Foreign(String name, double[] pos, String district, boolean spawn, int other) {
	}

	@Test
	void anotherPackagesLandmarkIsReadByItsFields() {
		Landmarks.Landmark l = Landmarks.adapt(new Foreign("Plaza", new double[] {1, 2, 3}, "Heywood", false, 5));
		assertNotNull(l);
		assertEquals("Plaza", l.name());
		assertEquals(new Vec3(1, 2, 3), l.pos());
		assertEquals("Heywood", l.district());
		assertFalse(l.spawn());
		assertNull(Landmarks.adapt("not a record"));
		assertNull(Landmarks.adapt(new HoloHooks.Match(null, -1, HoloRules.Role.PLAYER)), "no name and no pos");
	}

	@Test
	void figureDensityPerSquare() {
		List<HoloLayers.Figure> figures = List.of(
			new HoloLayers.Figure(1, 1, false), new HoloLayers.Figure(10, 20, true), new HoloLayers.Figure(63, 63, true),
			new HoloLayers.Figure(-1, 5, false));
		List<dev.cyberblock.holo.HoloWire.LayerItem> all = HoloLayers.density(figures, false);
		assertEquals(2, all.size());
		assertEquals(32.0, all.getFirst().x(), "the busiest square first, at its centre");
		assertEquals(32.0, all.getFirst().z());
		assertEquals(1.0F, all.getFirst().v());
		assertEquals("3", all.getFirst().label());
		assertEquals(-32.0, all.get(1).x());
		assertEquals(1.0F / 3.0F, all.get(1).v(), 1e-6);
		List<dev.cyberblock.holo.HoloWire.LayerItem> hot = HoloLayers.density(figures, true);
		assertEquals(1, hot.size());
		assertEquals("2", hot.getFirst().label());
		assertTrue(HoloLayers.density(List.of(), true).isEmpty());
	}

	@Test
	void onlyKnownLayersCanBeRegistered() {
		assertThrows(IllegalArgumentException.class, () -> HoloLayers.register("weather", (server, viewer) -> List.of()));
	}
}
