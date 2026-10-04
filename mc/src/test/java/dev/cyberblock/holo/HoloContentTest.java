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
			new HoloLayers.Figure(1, 1, false, null), new HoloLayers.Figure(10, 20, true, null), new HoloLayers.Figure(63, 63, true, null),
			new HoloLayers.Figure(-1, 5, false, null));
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
	void theDensityLayersCountOnlyTheFiguresOfHostsTheViewerMaySee() {
		java.util.UUID seen = java.util.UUID.randomUUID(), hidden = java.util.UUID.randomUUID();
		HoloLayers.Figure a = new HoloLayers.Figure(1, 1, true, seen), b = new HoloLayers.Figure(500, 500, true, hidden);
		HoloLayers.Figure nobody = new HoloLayers.Figure(900, 900, false, null);
		List<HoloLayers.Figure> kept = HoloLayers.seenBy(List.of(a, b, nobody), java.util.Set.of(seen));
		assertEquals(List.of(a), kept, "a hidden (or not visible) host's figures would mark where that host is");
		assertTrue(HoloLayers.seenBy(List.of(a, b, nobody), java.util.Set.of()).isEmpty());
	}

	@Test
	void hidingAPinHidesItsOwnersBeacons() {
		java.util.UUID owner = java.util.UUID.randomUUID(), other = java.util.UUID.randomUUID();
		Beacons beacons = new Beacons();
		beacons.add(new Beacons.Beacon(new net.minecraft.core.BlockPos(1, 64, 1), owner, "a", "", -1, false));
		beacons.add(new Beacons.Beacon(new net.minecraft.core.BlockPos(2, 64, 2), other, "b", "", -1, false));
		beacons.setHidden(owner, true);
		assertTrue(beacons.byId("1,64,1").hidden());
		assertFalse(beacons.byId("2,64,2").hidden());
		beacons.setHidden(owner, false);
		assertFalse(beacons.byId("1,64,1").hidden());
	}

	@Test
	void survivalPlayersCraftTheSpawnBeacon() throws java.io.IOException {
		JsonObject recipe = json(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/cyberblock/recipe/spawn_beacon.json")));
		assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
		assertEquals("cyberblock:" + HoloBlocks.SPAWN_BEACON_ID, recipe.getAsJsonObject("result").get("id").getAsString());
	}

	@Test
	void onlyKnownLayersCanBeRegistered() {
		assertThrows(IllegalArgumentException.class, () -> HoloLayers.register("weather", (server, viewer) -> List.of()));
	}
}
