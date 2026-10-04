package dev.cyberblock.hack.breach;

import java.util.Arrays;
import java.util.List;

/**
 * The Breach Protocol rules both sides use (megaplan 6-B1), pure: which cell a pick may take, when a daemon counts as uploaded, and
 * how strong the result is. The server decides with these; the client only greys cells with the same rule.
 */
public final class BreachRules {
	/** Strength 2 from this many uploaded codes (the solved daemons' lengths added up), strength 3 from {@link #STRONG}. */
	static final int MEDIUM = 4, STRONG = 7;

	private BreachRules() {
	}

	/**
	 * May pick number {@code k} (0-based) take {@code cell} of an {@code n} x {@code n} grid, after {@code picks[0..k-1]}? The first pick
	 * is in the top row; odd picks stay in the previous pick's column, even picks in its row. Whether the cell is still free is the
	 * caller's check.
	 */
	public static boolean allowed(final int n, final int[] picks, final int k, final int cell) {
		if (cell < 0 || cell >= n * n) {
			return false;
		}

		if (k == 0) {
			return cell / n == 0;
		}

		int previous = picks[k - 1];
		return k % 2 == 1 ? cell % n == previous % n : cell / n == previous / n;
	}

	/** Do the first {@code length} buffer codes hold {@code seq} as a contiguous run? */
	public static boolean uploaded(final int[] buffer, final int length, final int[] seq) {
		for (int start = 0; start + seq.length <= length; start++) {
			int i = 0;
			while (i < seq.length && buffer[start + i] == seq[i]) {
				i++;
			}

			if (i == seq.length) {
				return true;
			}
		}

		return false;
	}

	/**
	 * How far a daemon is under way (the screen's guide): the longest start of {@code seq}, shorter than the whole, that the buffer ends
	 * with; 0 when the next pick would have to start it afresh.
	 */
	public static int matched(final int[] buffer, final int length, final int[] seq) {
		for (int k = Math.min(length, seq.length - 1); k > 0; k--) {
			if (Arrays.equals(buffer, length - k, length, seq, 0, k)) {
				return k;
			}
		}

		return 0;
	}

	/**
	 * How strong the uploaded daemons hit (the {@code daemon} DOWN's {@code strength}): more and longer sequences are stronger. 0 when
	 * none was uploaded; else the lengths added up: under {@link #MEDIUM} is 1, under {@link #STRONG} is 2, from there 3. One
	 * two-code daemon is 1, a four-code daemon or two two-code ones are 2, a three- and a four-code daemon (or 2 + 2 + 3) are 3.
	 */
	public static int strength(final List<Integer> uploadedLengths) {
		if (uploadedLengths.isEmpty()) {
			return 0;
		}

		int codes = uploadedLengths.stream().mapToInt(Integer::intValue).sum();
		return codes >= STRONG ? 3 : codes >= MEDIUM ? 2 : 1;
	}
}
