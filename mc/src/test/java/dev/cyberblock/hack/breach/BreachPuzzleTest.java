package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.hack.breach.BreachPuzzle.DaemonDef;
import dev.cyberblock.hack.breach.BreachPuzzle.Tier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 6-B1: the seeded generator: sizes by tier, the content's fixed sequences, weights, and every daemon solvable within the buffer. */
class BreachPuzzleTest {
	/** 6A's default sequences: open_door BD 1C, disable_cameras 55 E9 7A, reveal_enemies 1C 55, overload FF 7A E9. */
	static final List<DaemonDef> POOL = List.of(new DaemonDef("open_door", new int[] {2, 0}, 6), new DaemonDef("disable_cameras", new int[] {1, 3, 4}, 6),
		new DaemonDef("reveal_enemies", new int[] {0, 1}, 5), new DaemonDef("overload", new int[] {5, 4, 3}, 3));
	static final List<Tier> TIERS = List.of(new Tier(5, 7, 1, 2, 60), new Tier(6, 6, 1, 3, 50), new Tier(7, 5, 2, 3, 45), new Tier(7, 8, 3, 3, 25),
		BreachContent.SHORT, new Tier(5, 4, 3, 3, 30));

	@Test
	void sameSeedSamePuzzle() {
		BreachPuzzle a = BreachPuzzle.generate(42L, TIERS.get(3), POOL), b = BreachPuzzle.generate(42L, TIERS.get(3), POOL);
		assertArrayEquals(a.grid(), b.grid());
		assertArrayEquals(a.solution(), b.solution());
		assertEquals(a.daemons().stream().map(BreachPuzzle.Daemon::id).toList(), b.daemons().stream().map(BreachPuzzle.Daemon::id).toList());
		assertFalse(Arrays.equals(a.grid(), BreachPuzzle.generate(43L, TIERS.get(3), POOL).grid()), "another seed, another grid");
	}

	@Test
	void shapeFollowsTheTierAndTheContent() {
		for (Tier t : TIERS) {
			for (long seed = 0; seed < 50; seed++) {
				BreachPuzzle p = BreachPuzzle.generate(seed, t, POOL);
				assertEquals(t.grid(), p.size());
				assertEquals(t.grid() * t.grid(), p.grid().length);
				assertEquals(t.buffer(), p.buffer());
				assertEquals(t.seconds(), p.seconds());
				assertTrue(p.daemons().size() >= 1 && p.daemons().size() <= t.daemonsMax());
				assertTrue(Arrays.stream(p.grid()).allMatch(c -> c >= 0 && c < BreachPuzzle.CODES.size()));
				assertEquals(p.daemons().size(), p.daemons().stream().map(BreachPuzzle.Daemon::id).distinct().count(), "each daemon once");
				for (BreachPuzzle.Daemon d : p.daemons()) {
					DaemonDef def = POOL.stream().filter(x -> x.id().equals(d.id())).findFirst().orElseThrow();
					assertArrayEquals(def.seq(), d.seq(), "the content's fixed sequence");
				}
			}
		}
	}

	@Test
	void theMinimumHoldsWhenTheBufferHasRoom() {
		for (long seed = 0; seed < 50; seed++) {
			assertEquals(3, BreachPuzzle.generate(seed, TIERS.get(3), POOL).daemons().size(), "buffer 8 holds any three of these");
			assertTrue(BreachPuzzle.generate(seed, TIERS.get(5), POOL).daemons().size() < 3, "no three of these fit buffer 4: some are dropped");
		}
	}

	@Test
	void heavierDaemonsComeMoreOften() {
		List<DaemonDef> pool = List.of(new DaemonDef("light", new int[] {0, 1}, 1), new DaemonDef("heavy", new int[] {2, 3}, 10));
		long heavy = 0;
		for (long seed = 0; seed < 300; seed++) {
			heavy += BreachPuzzle.generate(seed, BreachContent.SHORT, pool).daemons().getFirst().id().equals("heavy") ? 1 : 0;
		}

		assertTrue(heavy > 220, "weight 10 against 1: " + heavy + " of 300");
	}

	@Test
	void joinOverlapsSequences() {
		assertArrayEquals(new int[] {2, 0, 1}, BreachPuzzle.merge(new int[] {2, 0}, new int[] {0, 1}));
		assertArrayEquals(new int[] {2, 0, 1}, BreachPuzzle.merge(new int[] {2, 0, 1}, new int[] {0, 1}), "inside: unchanged");
		assertArrayEquals(new int[] {2, 0, 5, 4}, BreachPuzzle.merge(new int[] {2, 0}, new int[] {5, 4}));
		assertEquals(3, BreachPuzzle.join(List.of(POOL.get(2), POOL.get(0))).length, "1C 55 + BD 1C -> BD 1C 55");
	}

	@Test
	void everyDaemonIsSolvableWithinTheBuffer() {
		for (Tier t : TIERS) {
			for (long seed = 0; seed < 100; seed++) {
				BreachPuzzle p = BreachPuzzle.generate(seed, t, POOL);
				for (BreachPuzzle.Daemon d : p.daemons()) {
					assertTrue(solvable(p, d.seq()), "seed " + seed + " " + t + " daemon " + d.id());
				}
			}
		}
	}

	@Test
	void thePlantedPathIsLegalAndUploadsEverything() {
		for (Tier t : TIERS) {
			for (long seed = 0; seed < 100; seed++) {
				BreachPuzzle p = BreachPuzzle.generate(seed, t, POOL);
				BreachGame g = new BreachGame(p, 0);
				for (int cell : p.solution()) {
					if (!g.ended()) {
						assertTrue(g.pick(cell, 1), "seed " + seed + ": every planted pick is legal");
					}
				}

				assertEquals(BreachGame.End.ALL_UPLOADED, g.end());
				assertEquals(p.daemons().size(), g.uploadedDaemons().size());
			}
		}
	}

	@Test
	void theTestToolsFailPathFillsTheBufferAndUploadsNothing() {
		int found = 0;
		for (Tier t : TIERS) {
			for (long seed = 0; seed < 40; seed++) {
				BreachPuzzle p = BreachPuzzle.generate(seed, t, POOL);
				int[] path = BreachCommands.clean(p);
				if (path != null) {
					found++;
					BreachGame g = new BreachGame(p, 0);
					for (int cell : path) {
						assertTrue(g.pick(cell, 1));
					}

					assertEquals(BreachGame.End.BUFFER_FULL, g.end());
					assertTrue(g.failed());
				}
			}
		}

		assertTrue(found > 200, "nearly every grid has such a path: " + found + " of 240");
	}

	@Test
	void badInputIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> BreachPuzzle.generate(1L, TIERS.getFirst(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Tier(8, 6, 1, 2, 30));
		assertThrows(IllegalArgumentException.class, () -> new Tier(5, 9, 1, 2, 30));
		assertThrows(IllegalArgumentException.class, () -> new Tier(5, 6, 1, 4, 30));
		assertThrows(IllegalArgumentException.class, () -> new Tier(5, 6, 3, 2, 30));
	}

	/** An independent solver: some legal pick sequence within the buffer holds seq as a contiguous run. */
	static boolean solvable(final BreachPuzzle p, final int[] seq) {
		return search(p, seq, new ArrayList<>(), new boolean[p.grid().length]);
	}

	private static boolean search(final BreachPuzzle p, final int[] seq, final List<Integer> picks, final boolean[] used) {
		int[] codes = picks.stream().mapToInt(c -> p.grid()[c]).toArray();
		String buffer = Arrays.toString(codes), want = Arrays.toString(seq);
		if (buffer.contains(want.substring(1, want.length() - 1))) {
			return true;
		}

		if (picks.size() == p.buffer() || started(codes, seq) + p.buffer() - picks.size() < seq.length) {
			return false;
		}

		int n = p.size();
		for (int cell = 0; cell < n * n; cell++) {
			int k = picks.size();
			boolean line = k == 0 ? cell / n == 0 : k % 2 == 1 ? cell % n == picks.get(k - 1) % n : cell / n == picks.get(k - 1) / n;
			if (line && !used[cell]) {
				used[cell] = true;
				picks.add(cell);
				boolean found = search(p, seq, picks, used);
				picks.removeLast();
				used[cell] = false;
				if (found) {
					return true;
				}
			}
		}

		return false;
	}

	/** The longest start of seq the buffer ends with (for pruning). */
	private static int started(final int[] codes, final int[] seq) {
		for (int k = Math.min(codes.length, seq.length); k > 0; k--) {
			if (Arrays.equals(Arrays.copyOfRange(codes, codes.length - k, codes.length), Arrays.copyOf(seq, k))) {
				return k;
			}
		}

		return 0;
	}
}
