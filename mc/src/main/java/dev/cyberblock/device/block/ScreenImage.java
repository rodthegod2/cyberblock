package dev.cyberblock.device.block;

/**
 * The procedural pictures on Night City's TVs and billboards (section 1.11, toggle bit 512 {@code screen}): original art drawn from
 * a pattern number, no image files. A screen of {@code w x h} item frames shows one picture of {@code w*128 x h*128} pixels; each
 * frame's map gets its 128 x 128 tile. Pixels are indices into {@link #INK} (ScreenFrames turns them into map colours). Pure and
 * deterministic: the same arguments give the same picture.
 */
public final class ScreenImage {
	/** The inks, as map colours (ScreenFrames maps them): the index is what {@link #tile} returns. */
	public enum Ink {
		BLACK, DARK, GREY, WHITE, CYAN, MAGENTA, YELLOW, ORANGE, RED, BLUE, PURPLE, GREEN
	}

	public static final int TILE = 128;
	public static final int PATTERNS = 8;
	static final Ink[] INK = Ink.values();
	/** Two accent pairs per pattern: the neon of Night City's ads. */
	private static final Ink[][] ACCENTS = {
		{Ink.MAGENTA, Ink.CYAN}, {Ink.YELLOW, Ink.MAGENTA}, {Ink.CYAN, Ink.ORANGE}, {Ink.RED, Ink.YELLOW},
		{Ink.GREEN, Ink.PURPLE}, {Ink.ORANGE, Ink.BLUE}, {Ink.PURPLE, Ink.CYAN}, {Ink.BLUE, Ink.MAGENTA}};

	private ScreenImage() {
	}

	/**
	 * Tile ({@code tx}, {@code ty}) of pattern {@code pattern} on a {@code w x h} screen, row-major 128 x 128 ink indices; {@code tx}
	 * counts from the left, {@code ty} from the top. An {@code on == false} screen is dark glass with a faint glare.
	 */
	public static byte[] tile(final int pattern, final int w, final int h, final int tx, final int ty, final boolean on) {
		if (w < 1 || h < 1 || tx < 0 || tx >= w || ty < 0 || ty >= h) {
			throw new IllegalArgumentException("tile " + tx + "," + ty + " of " + w + "x" + h);
		}

		byte[] out = new byte[TILE * TILE];
		int width = w * TILE;
		int height = h * TILE;
		int p = Math.floorMod(pattern, PATTERNS);
		for (int y = 0; y < TILE; y++) {
			for (int x = 0; x < TILE; x++) {
				int gx = tx * TILE + x;
				int gy = ty * TILE + y;
				Ink ink = on ? lit(p, gx, gy, width, height) : off(gx, gy, width, height);
				out[y * TILE + x] = (byte) ink.ordinal();
			}
		}

		return out;
	}

	/** A dark screen: black glass, one soft diagonal glare band. */
	private static Ink off(final int x, final int y, final int width, final int height) {
		int d = x * height / Math.max(width, 1) + y;
		return Math.floorMod(d - height / 3, height * 2) < height / 10 ? Ink.DARK : Ink.BLACK;
	}

	/** A lit screen: a neon frame, then a skyline at dusk (even patterns) or a logo and text bars (odd patterns), with scanlines. */
	private static Ink lit(final int p, final int x, final int y, final int width, final int height) {
		Ink a = ACCENTS[p][0];
		Ink b = ACCENTS[p][1];
		int border = Math.max(3, Math.min(width, height) / 40);
		if (x < border || y < border || x >= width - border || y >= height - border) {
			return a;
		}

		if (y % 4 == 3) {
			return Ink.BLACK;
		}

		return p % 2 == 0 ? skyline(p, a, b, x, y, width, height) : logo(p, a, b, x, y, width, height);
	}

	/** Three sky bands over black towers with lit windows; the tower heights come from the pattern. */
	private static Ink skyline(final int p, final Ink a, final Ink b, final int x, final int y, final int width, final int height) {
		int towerWidth = Math.max(8, width / 12);
		int tower = x / towerWidth;
		int top = height / 3 + (int) (hash(p, tower) % (height / 3));
		if (y >= top) {
			boolean window = (x % towerWidth) % 6 == 2 && (y - top) % 8 == 3 && hash(p, tower * 131 + y / 8) % 3 == 0;
			return window ? Ink.YELLOW : Ink.BLACK;
		}

		if (y < height / 6) {
			return Ink.PURPLE;
		}

		return y < height / 4 ? b : a;
	}

	/** A ring logo on the left, three text bars on the right, on a dark ground. */
	private static Ink logo(final int p, final Ink a, final Ink b, final int x, final int y, final int width, final int height) {
		int cx = width / 4;
		int cy = height / 2;
		int r = Math.min(width, height) / 4;
		long dx = x - cx;
		long dy = y - cy;
		long d2 = dx * dx + dy * dy;
		if (d2 <= (long) r * r && d2 >= (long) (r * 2 / 3) * (r * 2 / 3)) {
			return a;
		}

		if (d2 < (long) (r / 3) * (r / 3)) {
			return b;
		}

		int left = width / 2;
		int bar = height / 8;
		for (int i = 0; i < 3; i++) {
			int barTop = height / 4 + i * bar * 2 - bar / 2;
			int barRight = width - width / 10 - (int) (hash(p, i) % (width / 5));
			if (y >= barTop && y < barTop + bar && x >= left && x < barRight) {
				return i == 0 ? Ink.WHITE : b;
			}
		}

		return Ink.DARK;
	}

	/** A small fixed hash (no Random: the picture must never change between runs or Java versions). */
	private static long hash(final int a, final int b) {
		long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return h & Long.MAX_VALUE;
	}
}
