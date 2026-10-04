package dev.cyberblock.client.holo;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.client.ui.UiModel;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.holo.HoloWire;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Megaplan 7-T1: the table's spawn list (with 7V's fast-travel hook) and a guest's map tile asks under the miniature. */
class HoloEntriesTest {
	private static HoloWire.SpawnPoint point(final String kind, final String id, final String name, final boolean ok) {
		return new HoloWire.SpawnPoint(kind, id, name, 1, 2, 3, "Watson", "Ada", ok, ok ? "" : "cyberblock.holo.cooldown");
	}

	@Test
	void groupedAndSortedWithFastTravelLast() {
		HoloWire.State s = new HoloWire.State(false, false, "player", false, 0, List.of(), List.of(
			point("friend", "00000000-0000-0000-0000-000000000001", "zed", true),
			point("landmark", "cyberblock:b", "Bravo", true),
			point("beacon", "1,2,3", "Ada", false),
			point("landmark", "cyberblock:a", "alpha", true)), List.of(), null);
		List<HoloFastTravel.Point> travel = List.of(new HoloFastTravel.Point("Kabuki market", 5, 6, "Watson", () -> {
		}));
		List<HoloEntries.Entry> e = HoloEntries.of(s, travel);
		assertEquals(List.of("landmark:cyberblock:a", "landmark:cyberblock:b", "beacon:1,2,3", "friend:00000000-0000-0000-0000-000000000001",
			"travel:Kabuki market"), e.stream().map(HoloEntries.Entry::key).toList());
		assertEquals("Watson", e.get(0).sub(), "a landmark shows its district");
		assertEquals("Ada", e.get(2).sub(), "a beacon its owner");
		assertEquals(HoloRules.Kind.BEACON, e.get(2).kind());
		assertFalse(e.get(2).ok());
		assertNotNull(e.get(4).point());
		assertNull(e.get(4).kind());
		assertSame(e.get(1), HoloEntries.find(e, "landmark:cyberblock:b"));
		assertNull(HoloEntries.find(e, null));
	}

	@Test
	void noStateNoHookNoEntries() {
		assertTrue(HoloEntries.of(null, List.of()).isEmpty());
	}

	@Test
	void initInstallsTheHostsFastTravelPoints() {
		Supplier<List<HoloFastTravel.Point>> before = HoloFastTravel.points;
		try {
			HoloFastTravel.points = HoloFastTravel.NONE;
			HoloFastTravel.install(); // what HoloClient.init does
			assertNotSame(HoloFastTravel.NONE, HoloFastTravel.points, "7V's points are hooked in");
			assertTrue(HoloFastTravel.list().isEmpty(), "no host linked (a guest): nothing listed");
		} finally {
			HoloFastTravel.points = before;
		}

		List<HoloFastTravel.Point> p = HoloFastTravel.of(List.of(new UiModel.ReplyPin(new UiModel.Pos(-120.5, 30, 64.25), "fasttravel",
			"Kabuki market", 3, "Watson")));
		assertEquals(1, p.size());
		assertEquals("Kabuki market", p.getFirst().name());
		assertEquals(-120.5, p.getFirst().x());
		assertEquals(64.25, p.getFirst().z(), "Minecraft x and z, as the Atlas draws the pin");
		assertEquals("Watson", p.getFirst().district());
	}

	@Test
	void guestTileAsksGoNearestFirstAndWait() {
		AtlasTiles view = new AtlasTiles();
		view.put(0, 0, 60, false); // tile (0, 0) known
		int[] window = {-2, -2, 2, 2};
		long now = 1_000_000_000_000L;
		List<long[]> first = HoloClient.due(window, view, now, 9);
		assertEquals(9, first.size());
		assertArrayEquals(new long[] {0, 0, 0}, first.getFirst(), "the centre first, as version 0");
		assertTrue(first.stream().allMatch(t -> Math.max(Math.abs(t[0]), Math.abs(t[1])) <= 1), "then the ring around it");
		List<long[]> rest = HoloClient.due(window, view, now, 100);
		assertEquals(25 - 9, rest.size(), "every tile once");
		assertTrue(HoloClient.due(window, view, now + HoloClient.MISSING_NANOS - 1, 100).isEmpty());
		List<long[]> again = HoloClient.due(window, view, now + HoloClient.MISSING_NANOS, 100);
		assertEquals(24, again.size(), "missing tiles again after 10 s, the known one only after 60 s");
		assertEquals(1, HoloClient.due(window, view, now + HoloClient.KNOWN_NANOS, 100).stream().filter(t -> t[0] == 0 && t[1] == 0).count());
	}
}
