package dev.cyberblock;

import java.util.ArrayList;
import java.util.List;

/** Solid column ranges from the host: {x, z, yBottom, yTop} quadruples, inclusive, Minecraft block coordinates. */
public final class Ranges {
	public record Range(int x, int z, int yBottom, int yTop) {
		public int height() {
			return this.yTop - this.yBottom + 1;
		}
	}

	private Ranges() {
	}

	public static List<Range> decode(final int[] c) {
		if (c.length % 4 != 0) {
			throw new IllegalArgumentException("ranges need quadruples, got " + c.length + " ints");
		}

		List<Range> out = new ArrayList<>(c.length / 4);
		for (int i = 0; i < c.length; i += 4) {
			if (c[i + 2] > c[i + 3]) {
				throw new IllegalArgumentException("inverted range at " + i / 4);
			}

			out.add(new Range(c[i], c[i + 1], c[i + 2], c[i + 3]));
		}

		return out;
	}
}
