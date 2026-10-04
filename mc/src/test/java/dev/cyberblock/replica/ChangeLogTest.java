package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Megaplan 3-R2: the Offline Builds log, per region, 30 days, with player name and time. */
class ChangeLogTest {
	private static final long DAY = 24L * 60 * 60 * 1000;
	private static final long T0 = 1_800_000_000_000L;

	@Test
	void changesAreKeptWithNameAndTimeAndReadAfterAMoment() {
		ChangeLog log = new ChangeLog();
		log.add(new ChangeLog.Change(BlockPos.asLong(1, 64, 1), true, "CbGuest1", T0));
		log.add(new ChangeLog.Change(BlockPos.asLong(2, 64, 1), false, "CbGuest2", T0 + 1000));
		List<ChangeLog.Change> after = log.after(T0);
		assertEquals(1, after.size(), "only those after the host left");
		assertEquals("CbGuest2", after.get(0).name());
		assertFalse(after.get(0).placed());
		assertEquals(2, log.after(T0 - 1).size());
	}

	@Test
	void thirtyDaysAreKept() {
		ChangeLog log = new ChangeLog();
		log.add(new ChangeLog.Change(BlockPos.asLong(1, 64, 1), true, "old", T0));
		log.add(new ChangeLog.Change(BlockPos.asLong(1, 65, 1), true, "recent", T0 + 29 * DAY));
		assertEquals(2, log.size());
		log.prune(T0 + 30 * DAY + 1);
		assertEquals(1, log.size());
		assertEquals("recent", log.after(0).get(0).name());
	}

	@Test
	void aFullRegionDropsItsOldest() {
		ChangeLog log = new ChangeLog();
		long other = BlockPos.asLong(600, 64, 0); // region (1, 0)
		log.add(new ChangeLog.Change(other, true, "elsewhere", T0));
		for (int i = 0; i <= ChangeLog.REGION_CAP; i++) {
			log.add(new ChangeLog.Change(BlockPos.asLong(i % 500, 64, i / 500), true, "builder" + i, T0 + i));
		}

		long region = ChangeLog.region(BlockPos.asLong(0, 64, 0));
		assertEquals(ChangeLog.REGION_CAP, log.inRegion(region));
		assertEquals(1, log.inRegion(ChangeLog.region(other)), "another region is untouched");
		assertTrue(log.after(0).stream().noneMatch(c -> c.name().equals("builder0")), "the oldest of the full region went");
	}

	@Test
	void regionsAre512Blocks() {
		assertEquals(ChangeLog.region(BlockPos.asLong(0, 0, 0)), ChangeLog.region(BlockPos.asLong(511, 200, 511)));
		assertNotEquals(ChangeLog.region(BlockPos.asLong(0, 0, 0)), ChangeLog.region(BlockPos.asLong(512, 0, 0)));
		assertNotEquals(ChangeLog.region(BlockPos.asLong(0, 0, 0)), ChangeLog.region(BlockPos.asLong(-1, 0, 0)));
	}

	@Test
	void savedWithTheWorld() {
		ChangeLog log = new ChangeLog();
		log.add(new ChangeLog.Change(BlockPos.asLong(-5, 70, 9), true, "CbGuest1", T0));
		JsonElement saved = ChangeLog.CODEC.encodeStart(JsonOps.INSTANCE, log).getOrThrow();
		ChangeLog back = ChangeLog.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		assertEquals(log.after(0), back.after(0));
		assertEquals(1, back.inRegion(ChangeLog.region(BlockPos.asLong(-5, 70, 9))));
	}

	@Test
	void onlyThePlayersOwnCellsInTheSameTickAreLogged() {
		assertTrue(ChangeLog.since(0).isEmpty(), "no world: nothing");
		// without a running world nothing is recorded (and nothing breaks)
		ChangeLog.acting("CbGuest1", 1L, 2L, 5);
		ChangeLog.changed(2L, true, 5);
		assertTrue(ChangeLog.since(0).isEmpty());

		ChangeLog.use(new ChangeLog());
		try {
			long hit = BlockPos.asLong(3, 64, 3), placed = BlockPos.asLong(3, 65, 3);
			ChangeLog.acting("CbGuest1", hit, placed, 7);
			ChangeLog.changed(placed, true, 7);
			ChangeLog.changed(BlockPos.asLong(9, 64, 9), true, 7); // not the cell the player used: a piston, water, a neighbour
			ChangeLog.changed(placed, false, 8); // the next tick: not the player any more
			List<ChangeLog.Change> logged = ChangeLog.since(0);
			assertEquals(1, logged.size());
			assertEquals(new ChangeLog.Change(placed, true, "CbGuest1", logged.get(0).time()), logged.get(0));
		} finally {
			ChangeLog.detach();
		}
	}
}
