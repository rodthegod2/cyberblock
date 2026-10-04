package dev.cyberblock.env;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.env.OfflineBuilds.Change;
import dev.cyberblock.env.OfflineBuilds.Summary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Plan 3-E3: what changed while a host was away, net per cell, by region. */
class OfflineBuildsTest {
	private static long cell(final int x, final int y, final int z) {
		return BlockPos.asLong(x, y, z);
	}

	@Test
	void placedAndRemovedCountNetPerCell() {
		List<Change> log = List.of(
			new Change(cell(10, 64, -21), true, "CbGuest1", 1),
			new Change(cell(11, 64, -21), true, "CbGuest1", 2),
			new Change(cell(12, 64, -21), false, "CbGuest2", 3),
			// placed then broken again: nothing changed there
			new Change(cell(13, 64, -21), true, "CbGuest1", 4),
			new Change(cell(13, 64, -21), false, "CbGuest2", 5),
			// broken then placed again (a block swapped): still solid, nothing new for the host
			new Change(cell(14, 64, -21), false, "CbGuest2", 6),
			new Change(cell(14, 64, -21), true, "CbGuest1", 7));
		Summary s = OfflineBuilds.summarize(log);
		assertEquals(2, s.placed());
		assertEquals(1, s.removed());
		assertEquals(List.of("CbGuest1", "CbGuest2"), s.players());
		assertEquals(3, s.cells().size());
	}

	@Test
	void thePlayerOfACellIsItsLastChanger() {
		Summary s = OfflineBuilds.summarize(List.of(new Change(cell(0, 64, 0), true, "A", 1), new Change(cell(0, 64, 0), false, "B", 2),
			new Change(cell(0, 64, 0), true, "C", 3)));
		assertEquals(1, s.placed());
		assertEquals(List.of("C"), s.players());
	}

	@Test
	void nothingChangedIsAnEmptySummary() {
		Summary s = OfflineBuilds.summarize(List.of());
		assertEquals(0, s.placed());
		assertEquals(0, s.removed());
		assertTrue(s.players().isEmpty());
	}

	@Test
	void namesAreListedUpToFour() {
		assertEquals("A", OfflineBuilds.names(List.of("A")));
		assertEquals("A, B", OfflineBuilds.names(List.of("A", "B")));
		assertEquals("A, B, C, D", OfflineBuilds.names(List.of("A", "B", "C", "D")));
		assertEquals("A, B, C, D +2", OfflineBuilds.names(List.of("A", "B", "C", "D", "E", "F")));
	}

	@Test
	void regionsAreMinecraftRegionFiles() {
		Summary s = OfflineBuilds.summarize(List.of(new Change(cell(10, 64, -21), true, "A", 1), new Change(cell(511, 64, 0), true, "A", 2),
			new Change(cell(512, 64, 0), false, "A", 3), new Change(cell(-1, 70, -513), true, "A", 4)));
		Map<OfflineBuilds.Region, List<OfflineBuilds.Cell>> regions = OfflineBuilds.byRegion(s.cells());
		assertEquals(List.of(new OfflineBuilds.Region(-1, -2), new OfflineBuilds.Region(0, -1), new OfflineBuilds.Region(0, 0),
			new OfflineBuilds.Region(1, 0)), new ArrayList<>(regions.keySet()), "west to east, north to south");
		assertEquals("r.0.-1 (x 0..511, z -512..-1)", new OfflineBuilds.Region(0, -1).label());
		assertEquals("r.-1.-2 (x -512..-1, z -1024..-513)", new OfflineBuilds.Region(-1, -2).label());
	}

	@Test
	void coordinatesAreCapped() {
		List<OfflineBuilds.Cell> cells = new ArrayList<>();
		for (int x = 0; x < 5; x++) {
			cells.add(new OfflineBuilds.Cell(cell(x, 64, -21), true, "A"));
		}

		assertEquals("0 64 -21, 1 64 -21, 2 64 -21, 3 64 -21, 4 64 -21", OfflineBuilds.coords(cells, 5));
		assertEquals("0 64 -21, 1 64 -21 (+3 more)", OfflineBuilds.coords(cells, 2));
	}

	@Test
	void aHostSeesEachAbsenceOnce() {
		assertFalse(OfflineBuilds.worthShowing(0L, null), "a first session has no absence");
		assertTrue(OfflineBuilds.worthShowing(1000L, null));
		assertTrue(OfflineBuilds.worthShowing(2000L, 1000L));
		assertFalse(OfflineBuilds.worthShowing(1000L, 1000L), "a second hello in the same session shows nothing again");
	}
}
