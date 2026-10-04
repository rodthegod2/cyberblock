package dev.cyberblock.hack.breach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * One Breach Protocol puzzle (megaplan 6-B1), built by a pure seeded generator: a square grid of hex codes ({@link #CODES}), a buffer,
 * 1-3 daemon sequences and a timer. The classic rule ({@link BreachRules#allowed}): the first pick is in the top row, then the picks
 * alternate between the column and the row of the previous pick, never a cell twice.
 *
 * <p>Daemon sequences are fixed by content (6A's {@code daemon} files). Every placed daemon is solvable within the buffer by
 * construction: the generator joins the chosen sequences into their shortest overlap (a daemon that no longer fits the buffer is
 * dropped, so a tight buffer may hold fewer than the tier's minimum), walks one legal path as long as the buffer, and writes that joined
 * run into the path's codes; the path's own picks then upload every daemon at once. The rest of the grid is random codes.
 * {@link #solution} keeps that path for the server's test tools and the tests; it never reaches a client.
 *
 * @param size grid side (4 for the short breach, else 5-7)
 * @param grid code index (0..5 into {@link #CODES}) per cell, row-major ({@code cell = row * size + col})
 * @param buffer how many picks the player has (4-8)
 * @param daemons the sequences to upload, distinct ids
 * @param seconds the timer, which starts at the first pick
 * @param solution the planted path (cells), one legal pick sequence that uploads every daemon
 */
public record BreachPuzzle(int size, int[] grid, int buffer, List<Daemon> daemons, int seconds, int[] solution) {
	/** The six hex codes of Breach Protocol, by index. */
	public static final List<String> CODES = List.of("1C", "55", "BD", "E9", "7A", "FF");
	private static final int ATTEMPTS = 64;

	/** One daemon to upload: its id on the wire (the content's action) and its code sequence. */
	public record Daemon(String id, int[] seq) {
	}

	/** The size of a breach (content kind {@code breach_tier} with its Firewall step, or the built-ins). */
	public record Tier(int grid, int buffer, int daemonsMin, int daemonsMax, int seconds) {
		public Tier {
			if (grid < 4 || grid > 7 || buffer < 4 || buffer > 8 || daemonsMin < 1 || daemonsMax > 3 || daemonsMin > daemonsMax || seconds < 3 || seconds > 300) {
				throw new IllegalArgumentException("tier out of range: grid 4-7, buffer 4-8, daemons 1-3 (min <= max), seconds 3-300");
			}
		}
	}

	/** A daemon the generator may place: its wire id, its fixed sequence (2-4 codes) and its weight (1-10). */
	public record DaemonDef(String id, int[] seq, int weight) {
	}

	/**
	 * Builds the puzzle for this seed: the tier's grid, buffer and timer, and between the tier's minimum and maximum of the given daemons
	 * (picked by weight, each id once, then fitted to the buffer). Pure: the same seed, tier and pool give the same puzzle.
	 *
	 * @param pool the daemons offered for this device (at least one)
	 */
	public static BreachPuzzle generate(final long seed, final Tier tier, final List<DaemonDef> pool) {
		if (pool.isEmpty()) {
			throw new IllegalArgumentException("no daemons to offer");
		}

		Random random = new Random(seed);
		List<DaemonDef> chosen = choose(pool, tier.daemonsMin() + random.nextInt(tier.daemonsMax() - tier.daemonsMin() + 1), random);
		int[] run = join(chosen);
		while (run.length > tier.buffer() && chosen.size() > 1) { // one sequence always fits: content has 2-4 codes, buffers 4-8
			chosen.removeLast();
			run = join(chosen);
		}

		int n = tier.grid();
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			int[] path = path(n, tier.buffer(), random);
			if (path == null) {
				continue;
			}

			int[] grid = new int[n * n];
			for (int i = 0; i < grid.length; i++) {
				grid[i] = random.nextInt(CODES.size());
			}

			int offset = random.nextInt(tier.buffer() - run.length + 1);
			for (int i = 0; i < path.length; i++) {
				grid[path[i]] = i >= offset && i < offset + run.length ? run[i - offset] : random.nextInt(CODES.size());
			}

			return new BreachPuzzle(n, grid, tier.buffer(), chosen.stream().map(d -> new Daemon(d.id(), d.seq().clone())).toList(), tier.seconds(), path);
		}

		throw new IllegalStateException("no legal path of " + tier.buffer() + " picks in a " + n + "x" + n + " grid");
	}

	/** Up to {@code count} daemons with distinct ids, drawn by weight without putting one back. */
	private static List<DaemonDef> choose(final List<DaemonDef> pool, final int count, final Random random) {
		List<DaemonDef> left = new ArrayList<>(pool);
		List<DaemonDef> out = new ArrayList<>();
		while (out.size() < count && !left.isEmpty()) {
			int total = left.stream().mapToInt(d -> Math.max(1, d.weight())).sum();
			int roll = random.nextInt(total);
			int i = 0;
			while (roll >= Math.max(1, left.get(i).weight())) {
				roll -= Math.max(1, left.get(i).weight());
				i++;
			}

			DaemonDef d = left.get(i);
			out.add(d);
			left.removeIf(x -> x.id().equals(d.id()));
		}

		return out;
	}

	/** The shortest run that holds every sequence (over every order of the at most three; the first shortest wins). */
	static int[] join(final List<DaemonDef> daemons) {
		int[] best = null;
		for (List<DaemonDef> order : orders(daemons)) {
			int[] run = new int[0];
			for (DaemonDef d : order) {
				run = merge(run, d.seq());
			}

			if (best == null || run.length < best.length) {
				best = run;
			}
		}

		return best == null ? new int[0] : best;
	}

	/** a, then b overlapped with a's end as far as they agree (b inside a: a itself). */
	static int[] merge(final int[] a, final int[] b) {
		if (BreachRules.uploaded(a, a.length, b)) {
			return a;
		}

		for (int k = Math.min(a.length, b.length - 1); k > 0; k--) {
			if (Arrays.equals(a, a.length - k, a.length, b, 0, k)) {
				int[] out = Arrays.copyOf(a, a.length + b.length - k);
				System.arraycopy(b, k, out, a.length, b.length - k);
				return out;
			}
		}

		int[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static List<List<DaemonDef>> orders(final List<DaemonDef> items) {
		if (items.size() <= 1) {
			return List.of(items);
		}

		List<List<DaemonDef>> out = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			List<DaemonDef> rest = new ArrayList<>(items);
			DaemonDef first = rest.remove(i);
			for (List<DaemonDef> tail : orders(rest)) {
				List<DaemonDef> order = new ArrayList<>(List.of(first));
				order.addAll(tail);
				out.add(order);
			}
		}

		return out;
	}

	/** A random legal pick sequence of this length (null when it ran into a dead end; the caller tries again). */
	private static int[] path(final int n, final int length, final Random random) {
		int[] picks = new int[length];
		boolean[] used = new boolean[n * n];
		for (int k = 0; k < length; k++) {
			List<Integer> options = new ArrayList<>();
			for (int cell = 0; cell < n * n; cell++) {
				if (!used[cell] && BreachRules.allowed(n, picks, k, cell)) {
					options.add(cell);
				}
			}

			if (options.isEmpty()) {
				return null;
			}

			picks[k] = options.get(random.nextInt(options.size()));
			used[picks[k]] = true;
		}

		return picks;
	}

	public int code(final int cell) {
		return this.grid[cell];
	}
}
