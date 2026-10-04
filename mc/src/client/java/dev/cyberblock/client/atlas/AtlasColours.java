package dev.cyberblock.client.atlas;

import java.util.Arrays;
import net.minecraft.world.level.material.MapColor;

/**
 * How the Atlas colours Night City (pure, tested), in the style of a vanilla filled map: vanilla map colours ({@link MapColor})
 * picked by how high a surface stands above the local ground, and vanilla's relief shading (a cell higher than its northern
 * neighbour is drawn brighter, a lower one darker, a drop of a building's height darkest). The local ground is the lowest known
 * dry cell within {@link #GROUND_RADIUS} cells (96 m): in a city every such window holds a street.
 * <ul>
 * <li>water: {@code WATER};</li>
 * <li>streets and ground (under 3 m above it): {@code SAND}, light like a map's paths;</li>
 * <li>low buildings (3-12 m): {@code COLOR_LIGHT_GRAY};</li>
 * <li>buildings (12-40 m): {@code STONE};</li>
 * <li>tall buildings (40-100 m): {@code COLOR_GRAY};</li>
 * <li>megabuildings and towers (100 m and more): {@code COLOR_BLACK}.</li>
 * </ul>
 */
public final class AtlasColours {
	/** Cells around a cell searched for its ground. */
	public static final int GROUND_RADIUS = 48;
	/** A drop of at least this many metres to the cell north of it is drawn darkest (a building's shadow edge). */
	static final int SHADOW_DROP = 6;
	private static final int NONE = Integer.MAX_VALUE;

	private AtlasColours() {
	}

	/** The map colour of a surface {@code above} metres over its ground. */
	public static MapColor band(final int above, final boolean water) {
		if (water) {
			return MapColor.WATER;
		}

		if (above < 3) {
			return MapColor.SAND;
		}

		if (above < 12) {
			return MapColor.COLOR_LIGHT_GRAY;
		}

		if (above < 40) {
			return MapColor.STONE;
		}

		return above < 100 ? MapColor.COLOR_GRAY : MapColor.COLOR_BLACK;
	}

	/** Vanilla's relief: brighter than the north neighbour when higher, darker when lower, darkest under a tall drop. */
	public static MapColor.Brightness relief(final int y, final int northY, final boolean northKnown) {
		if (!northKnown || y == northY) {
			return MapColor.Brightness.NORMAL;
		}

		if (y > northY) {
			return MapColor.Brightness.HIGH;
		}

		return northY - y >= SHADOW_DROP ? MapColor.Brightness.LOWEST : MapColor.Brightness.LOW;
	}

	/**
	 * The ARGB pixels (0 = unknown, transparent) of the {@code size} x {@code size} cells in the middle of {@code cells}, a square of
	 * {@code size + 2 * margin} cells ({@code cells[z * full + x]}, packed as {@link AtlasTiles}); the ground of each cell is looked
	 * for within {@code radius} cells (at most {@code margin}).
	 */
	public static int[] shade(final short[] cells, final int size, final int margin, final int radius) {
		int full = size + 2 * margin;
		int[] ground = groundMin(cells, full, Math.min(radius, margin));
		int[] out = new int[size * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = (z + margin) * full + (x + margin);
				short cell = cells[i];
				if (!AtlasTiles.known(cell)) {
					continue;
				}

				int y = AtlasTiles.height(cell);
				boolean water = AtlasTiles.water(cell);
				int base = ground[i] == NONE ? y : ground[i];
				short north = z + margin > 0 ? cells[i - full] : 0;
				MapColor colour = band(y - base, water);
				MapColor.Brightness brightness = water ? MapColor.Brightness.NORMAL
					: relief(y, AtlasTiles.known(north) ? AtlasTiles.height(north) : y, AtlasTiles.known(north) && !AtlasTiles.water(north));
				out[z * size + x] = colour.calculateARGBColor(brightness);
			}
		}

		return out;
	}

	/** Per cell the lowest known dry height within {@code radius} cells (a square window), NONE where there is none. */
	static int[] groundMin(final short[] cells, final int full, final int radius) {
		int[] heights = new int[cells.length];
		for (int i = 0; i < cells.length; i++) {
			heights[i] = AtlasTiles.known(cells[i]) && !AtlasTiles.water(cells[i]) ? AtlasTiles.height(cells[i]) : NONE;
		}

		int[] rows = new int[cells.length];
		int[] line = new int[full], window = new int[full];
		for (int z = 0; z < full; z++) {
			System.arraycopy(heights, z * full, line, 0, full);
			slidingMin(line, radius, window);
			System.arraycopy(window, 0, rows, z * full, full);
		}

		int[] out = new int[cells.length];
		for (int x = 0; x < full; x++) {
			for (int z = 0; z < full; z++) {
				line[z] = rows[z * full + x];
			}

			slidingMin(line, radius, window);
			for (int z = 0; z < full; z++) {
				out[z * full + x] = window[z];
			}
		}

		return out;
	}

	/** out[i] = min(in[i - r .. i + r]) (clipped at the ends), with a monotonic deque: O(n). */
	static void slidingMin(final int[] in, final int r, final int[] out) {
		int n = in.length;
		int[] deque = new int[n];
		int head = 0, tail = 0, next = 0;
		Arrays.fill(out, NONE);
		for (int i = 0; i < n; i++) {
			// the window of i is [i - r, i + r]: take in every index up to i + r
			while (next < n && next <= i + r) {
				while (tail > head && in[deque[tail - 1]] >= in[next]) {
					tail--;
				}

				deque[tail++] = next++;
			}

			while (deque[head] < i - r) {
				head++;
			}

			out[i] = in[deque[head]];
		}
	}

	/** One cell per {@code factor} x {@code factor} block of the {@code span}-wide square: the block's centre if known, else any. */
	static short[] downsample(final short[] cells, final int span, final int factor) {
		int n = span / factor;
		short[] out = new short[n * n];
		for (int bz = 0; bz < n; bz++) {
			for (int bx = 0; bx < n; bx++) {
				short pick = cells[(bz * factor + factor / 2) * span + bx * factor + factor / 2];
				for (int i = 0; i < factor * factor && !AtlasTiles.known(pick); i++) {
					pick = cells[(bz * factor + i / factor) * span + bx * factor + i % factor];
				}

				out[bz * n + bx] = pick;
			}
		}

		return out;
	}
}
