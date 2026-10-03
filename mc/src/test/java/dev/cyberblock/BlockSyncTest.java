package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.BlockEvents.Op;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class BlockSyncTest {
	private static long cell(final int x, final int y, final int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static long[] cells(final int n) {
		long[] out = new long[n];
		for (int i = 0; i < n; i++) {
			out[i] = cell(i, 64, -i);
		}

		return out;
	}

	/** Runs the resync for this many server ticks: the tick of each message and the message. */
	private static List<Object[]> run(final BlockSync sync, final int ticks) {
		List<Object[]> sent = new ArrayList<>();
		for (int tick = 0; tick < ticks; tick++) {
			String m = sync.next();
			if (m != null) {
				sent.add(new Object[] {tick, JsonParser.parseString(m).getAsJsonObject()});
			}
		}

		return sent;
	}

	private static Set<Long> cellsOf(final List<Object[]> sent) {
		Set<Long> out = new HashSet<>();
		for (Object[] s : sent) {
			JsonArray c = ((JsonObject) s[1]).getAsJsonArray("c");
			assertEquals(0, c.size() % 3);
			for (int i = 0; i < c.size(); i += 3) {
				out.add(cell(c.get(i).getAsInt(), c.get(i + 1).getAsInt(), c.get(i + 2).getAsInt()));
			}
		}

		return out;
	}

	@Test
	void packingRoundTrips() {
		int[][] positions = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 2031, -29_999_999}, {-30_000_000, -2032, 30_000_000}, {123, 70, -456}};
		for (int[] p : positions) {
			long packed = BlockPos.asLong(p[0], p[1], p[2]);
			assertEquals(p[0], BlockPos.getX(packed));
			assertEquals(p[1], BlockPos.getY(packed));
			assertEquals(p[2], BlockPos.getZ(packed));
			assertEquals(new BlockPos(p[0], p[1], p[2]), BlockPos.of(packed));
		}
	}

	@Test
	void resetFirstThenAtMost64PerMessageFiveASecond() {
		BlockSync sync = new BlockSync();
		sync.start(cells(150));
		List<Object[]> sent = run(sync, 40);
		assertEquals(3, sent.size());
		assertEquals(List.of(0, 4, 8), sent.stream().map(s -> s[0]).toList());
		JsonObject first = (JsonObject) sent.get(0)[1];
		assertEquals("blocks", first.get("t").getAsString());
		assertTrue(first.get("reset").getAsBoolean());
		assertEquals(64 * 3, first.getAsJsonArray("c").size());
		assertFalse(((JsonObject) sent.get(1)[1]).get("reset").getAsBoolean());
		assertEquals(64 * 3, ((JsonObject) sent.get(1)[1]).getAsJsonArray("c").size());
		assertEquals(22 * 3, ((JsonObject) sent.get(2)[1]).getAsJsonArray("c").size());
		assertEquals(150, cellsOf(sent).size());
		assertFalse(sync.running());
	}

	@Test
	void anEmptyWorldStillGetsTheReset() {
		BlockSync sync = new BlockSync();
		sync.start(new long[0]);
		List<Object[]> sent = run(sync, 20);
		assertEquals(1, sent.size());
		assertEquals("{\"t\":\"blocks\",\"reset\":true,\"c\":[]}", Wire.blocks(true, new long[0]));
		assertTrue(((JsonObject) sent.get(0)[1]).get("reset").getAsBoolean());
	}

	@Test
	void keepsTheOrderItWasGiven() {
		BlockSync sync = new BlockSync();
		long[] c = cells(3);
		sync.start(c);
		JsonArray sent = JsonParser.parseString(sync.next()).getAsJsonObject().getAsJsonArray("c");
		assertEquals(List.of(0, 64, 0, 1, 64, -1, 2, 64, -2), sent.asList().stream().map(e -> e.getAsInt()).toList());
	}

	@Test
	void aLiveEventReplacesTheWaitingCopy() {
		BlockSync sync = new BlockSync();
		long[] c = cells(100);
		sync.start(c);
		sync.next(); // the reset and the first 64
		sync.forget(c[80]); // removed by the player: the live "remove" goes out by itself
		Set<Long> rest = cellsOf(run(sync, 10));
		assertEquals(35, rest.size());
		assertFalse(rest.contains(c[80]));
	}

	@Test
	void aNewHelloStartsAgainAndClearStops() {
		BlockSync sync = new BlockSync();
		sync.start(cells(200));
		sync.next();
		sync.start(cells(10));
		List<Object[]> sent = run(sync, 20);
		assertEquals(1, sent.size());
		assertTrue(((JsonObject) sent.get(0)[1]).get("reset").getAsBoolean());
		sync.start(cells(10));
		sync.clear();
		assertNull(sync.next());
		assertFalse(sync.running());
	}

	@Test
	void nearestFirst() {
		long[] c = {cell(100, 64, 0), cell(1, 64, 0), cell(-10, 64, 0), cell(0, 70, 0)};
		BlockSync.nearestFirst(c, 0, 64, 0);
		assertArrayEquals(new long[] {cell(1, 64, 0), cell(0, 70, 0), cell(-10, 64, 0), cell(100, 64, 0)}, c);
	}

	@Test
	void addAndRemoveKeepTheSetExact() {
		PlacedBlocks placed = new PlacedBlocks();
		long a = cell(1, 2, 3), b = cell(-4, 5, -6);
		placed.apply(a, Op.ADD);
		placed.apply(a, Op.ADD);
		placed.apply(b, Op.ADD);
		assertEquals(2, placed.size());
		assertTrue(placed.isDirty());
		placed.setDirty(false);
		placed.apply(cell(9, 9, 9), Op.REMOVE); // never there: nothing changes
		assertFalse(placed.isDirty());
		placed.apply(a, Op.REMOVE);
		assertTrue(placed.isDirty());
		assertFalse(placed.contains(a));
		assertTrue(placed.contains(b));
		assertArrayEquals(new long[] {b}, placed.snapshot());
	}

	@Test
	void savesAndLoadsEveryCell() {
		PlacedBlocks placed = new PlacedBlocks(cells(500));
		Tag tag = PlacedBlocks.CODEC.encodeStart(NbtOps.INSTANCE, placed).getOrThrow();
		PlacedBlocks loaded = PlacedBlocks.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(500, loaded.size());
		for (long c : cells(500)) {
			assertTrue(loaded.contains(c));
		}
	}
}
