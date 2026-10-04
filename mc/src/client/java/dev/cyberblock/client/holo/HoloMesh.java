package dev.cyberblock.client.holo;

import dev.cyberblock.client.atlas.AtlasColours;
import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.holo.HoloWire;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.material.MapColor;

/**
 * The Holo-Table's miniature as geometry (pure, tested): a height grid of {@code n x n} columns over the shared Night City map
 * (AtlasTiles cells, coloured like the Atlas by {@link AtlasColours#shade}) with the server's replica detail around the players laid
 * over it (1-block columns in their map colours), and from it, for one camera, the faces to draw as screen parallelograms in
 * back-to-front order (the painter's order: a grid of columns sorted by their depth is drawn correctly by an orthographic camera).
 *
 * <p>A quad is 7 floats: {@code p0x, p0y, ux, uy, vx, vy, colour} (the corner and the two edge vectors in GUI px, the ARGB colour
 * as float bits); the screen draws it as a unit square under the affine pose {@code (u, v, p0)}, so every face is one ordinary
 * GUI fill. Every quad's {@code u x v} is positive (the winding of an untransformed fill).
 */
public final class HoloMesh {
	public static final int N = 64;
	/** Grid cells around the window that only serve the Atlas colouring (its ground search). */
	static final int MARGIN = 8;
	static final int FLOATS = 7;
	private static final int TINT = 0x40E0FF;
	private static final float TINT_SHARE = 0.15F;
	private static final int PLATE = 0xFF0B1E2A;

	/** The packed Atlas cell (AtlasTiles format, 0 unknown) of 2-m cell (cx, cz). */
	@FunctionalInterface
	public interface Cells {
		short get(int cx, int cz);
	}

	/** Column heights (NaN = unknown) and ARGB colours, {@code [z * n + x]}, of cells {@code step} blocks wide from (x0, z0). */
	public record Grid(int n, double x0, double z0, double step, float[] heights, int[] colours, float base) {
		public float floor() {
			return this.base - 1.0F;
		}
	}

	/** Reusable quad storage. */
	public static final class Quads {
		float[] data = new float[FLOATS * 1024];
		int count;
		private long[] order = new long[0];

		public int count() {
			return this.count;
		}

		public float[] data() {
			return this.data;
		}

		void add(final double p0x, final double p0y, final double ux, final double uy, final double vx, final double vy, final int colour) {
			double det = ux * vy - uy * vx;
			if (Math.abs(det) < 1e-4) {
				return; // seen edge-on
			}

			if (this.data.length < (this.count + 1) * FLOATS) {
				this.data = Arrays.copyOf(this.data, this.data.length * 2);
			}

			int i = this.count++ * FLOATS;
			this.data[i] = (float) p0x;
			this.data[i + 1] = (float) p0y;
			// the same parallelogram, wound like an untransformed fill
			this.data[i + 2] = (float) (det > 0 ? ux : vx);
			this.data[i + 3] = (float) (det > 0 ? uy : vy);
			this.data[i + 4] = (float) (det > 0 ? vx : ux);
			this.data[i + 5] = (float) (det > 0 ? vy : uy);
			this.data[i + 6] = Float.intBitsToFloat(colour);
		}
	}

	private HoloMesh() {
	}

	/**
	 * The grid of {@code n x n} cells covering {@code radius} blocks around (cx, cz), aligned to multiples of its step so that panning
	 * never makes it shimmer. A cell takes the highest of a few samples (detail first, then the map).
	 */
	public static Grid build(final int n, final double cx, final double cz, final double radius, final Cells cells, final List<HoloWire.Detail> detail) {
		double step = 2.0 * radius / n;
		double x0 = Math.floor((cx - radius) / step) * step, z0 = Math.floor((cz - radius) / step) * step;
		int full = n + 2 * MARGIN;
		short[] packed = new short[full * full];
		int[] detailColour = new int[full * full];
		int k = Math.clamp((int) Math.ceil(step / AtlasTiles.CELL), 1, 3);
		for (int gz = 0; gz < full; gz++) {
			for (int gx = 0; gx < full; gx++) {
				double wx = x0 + (gx - MARGIN) * step, wz = z0 + (gz - MARGIN) * step;
				int best = Integer.MIN_VALUE, bestColour = -1;
				short bestCell = 0;
				for (int s = 0; s < k * k; s++) {
					double px = wx + (s % k + 0.5) * step / k, pz = wz + (s / k + 0.5) * step / k;
					int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
					int[] d = detailAt(detail, bx, bz);
					if (d != null) {
						if (d[0] > best) {
							best = d[0];
							bestColour = d[1];
							bestCell = AtlasTiles.encode(d[0], false);
						}

						continue;
					}

					short cell = cells.get(Math.floorDiv(bx, AtlasTiles.CELL), Math.floorDiv(bz, AtlasTiles.CELL));
					if (AtlasTiles.known(cell) && AtlasTiles.height(cell) > best) {
						best = AtlasTiles.height(cell);
						bestColour = -1;
						bestCell = cell;
					}
				}

				packed[gz * full + gx] = bestCell;
				detailColour[gz * full + gx] = bestColour;
			}
		}

		int[] shaded = AtlasColours.shade(packed, n, MARGIN, MARGIN);
		float[] heights = new float[n * n];
		int[] colours = new int[n * n];
		float base = Float.NaN;
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int fi = (z + MARGIN) * full + (x + MARGIN), i = z * n + x;
				short cell = packed[fi];
				if (!AtlasTiles.known(cell)) {
					heights[i] = Float.NaN;
					continue;
				}

				heights[i] = AtlasTiles.height(cell);
				base = Float.isNaN(base) ? heights[i] : Math.min(base, heights[i]);
				int colour = shaded[i];
				if (detailColour[fi] > 0) {
					short north = packed[fi - full];
					MapColor.Brightness b = AtlasColours.relief(AtlasTiles.height(cell), AtlasTiles.known(north) ? AtlasTiles.height(north) : 0, AtlasTiles.known(north));
					colour = MapColor.byId(detailColour[fi]).calculateARGBColor(b);
				}

				colours[i] = tint(colour);
			}
		}

		return new Grid(n, x0, z0, step, heights, colours, Float.isNaN(base) ? 0.0F : base);
	}

	/** {height, map colour id} of the detail column at (x, z), or null when no patch knows it. */
	private static int[] detailAt(final List<HoloWire.Detail> detail, final int x, final int z) {
		for (HoloWire.Detail d : detail) {
			if (d.covers(x, z)) {
				int i = d.index(x, z);
				int id = d.colours()[i] & 0xFF;
				if (d.heights()[i] != HoloWire.UNKNOWN && id > 0 && id < 64) {
					return new int[] {d.heights()[i], id};
				}
			}
		}

		return null;
	}

	/** Opaque, a little toward the hologram's cyan. */
	static int tint(final int argb) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		r = Math.round(r + (((TINT >> 16) & 0xFF) - r) * TINT_SHARE);
		g = Math.round(g + (((TINT >> 8) & 0xFF) - g) * TINT_SHARE);
		b = Math.round(b + ((TINT & 0xFF) - b) * TINT_SHARE);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	static int shade(final int argb, final float f) {
		int r = Math.round(((argb >> 16) & 0xFF) * f), g = Math.round(((argb >> 8) & 0xFF) * f), b = Math.round((argb & 0xFF) * f);
		return (argb & 0xFF000000) | r << 16 | g << 8 | b;
	}

	/**
	 * The faces of {@code g} for this camera, back to front, into {@code out} (cleared first): the table's plate, then per column
	 * (far to near) its sides that face the viewer and stand above the neighbour, then its top. The screen centre (sx0, sy0) shows
	 * the camera's centre; {@code scale} is GUI px per block.
	 */
	public static Quads quads(final Grid g, final HoloCamera cam, final double sx0, final double sy0, final double scale, final Quads out) {
		out.count = 0;
		int n = g.n();
		double s = g.step(), floor = g.floor();
		double xe = g.x0() + n * s, ze = g.z0() + n * s;
		face(out, cam, sx0, sy0, scale, g.x0(), floor, g.z0(), xe, floor, g.z0(), g.x0(), floor, ze, PLATE);
		if (out.order.length < n * n) {
			out.order = new long[n * n];
		}

		int m = 0;
		double reach = 2.0 * n * s;
		for (int i = 0; i < n * n; i++) {
			if (!Float.isNaN(g.heights()[i])) {
				double x = g.x0() + (i % n + 0.5) * s, z = g.z0() + (i / n + 0.5) * s;
				long key = (long) ((cam.depth(x, z) + reach) * 64.0);
				out.order[m++] = key << 13 | i;
			}
		}

		Arrays.sort(out.order, 0, m);
		boolean east = cam.faces(1, 0), west = cam.faces(-1, 0), south = cam.faces(0, 1), north = cam.faces(0, -1);
		for (int o = 0; o < m; o++) {
			int i = (int) (out.order[o] & 0x1FFF), cxI = i % n, czI = i / n;
			double h = g.heights()[i], xa = g.x0() + cxI * s, za = g.z0() + czI * s, xb = xa + s, zb = za + s;
			int c = g.colours()[i];
			if (south) {
				double hn = neighbour(g, cxI, czI + 1, floor);
				if (hn < h) {
					face(out, cam, sx0, sy0, scale, xa, hn, zb, xb, hn, zb, xa, h, zb, shade(c, 0.8F));
				}
			}

			if (north) {
				double hn = neighbour(g, cxI, czI - 1, floor);
				if (hn < h) {
					face(out, cam, sx0, sy0, scale, xa, hn, za, xb, hn, za, xa, h, za, shade(c, 0.8F));
				}
			}

			if (east) {
				double hn = neighbour(g, cxI + 1, czI, floor);
				if (hn < h) {
					face(out, cam, sx0, sy0, scale, xb, hn, za, xb, hn, zb, xb, h, za, shade(c, 0.65F));
				}
			}

			if (west) {
				double hn = neighbour(g, cxI - 1, czI, floor);
				if (hn < h) {
					face(out, cam, sx0, sy0, scale, xa, hn, za, xa, hn, zb, xa, h, za, shade(c, 0.65F));
				}
			}

			face(out, cam, sx0, sy0, scale, xa, h, za, xb, h, za, xa, h, zb, c);
		}

		return out;
	}

	/** The neighbour column's height, the floor when it is unknown or outside the grid. */
	private static double neighbour(final Grid g, final int x, final int z, final double floor) {
		if (x < 0 || z < 0 || x >= g.n() || z >= g.n()) {
			return floor;
		}

		float h = g.heights()[z * g.n() + x];
		return Float.isNaN(h) ? floor : h;
	}

	/** One face from three world corners: p0, p0 + u, p0 + v. */
	private static void face(final Quads out, final HoloCamera cam, final double sx0, final double sy0, final double scale, final double x0, final double y0,
		final double z0, final double x1, final double y1, final double z1, final double x2, final double y2, final double z2, final int colour) {
		double ax = cam.sx(x0, z0, sx0, scale), ay = cam.sy(x0, y0, z0, sy0, scale);
		double bx = cam.sx(x1, z1, sx0, scale), by = cam.sy(x1, y1, z1, sy0, scale);
		double dx = cam.sx(x2, z2, sx0, scale), dy = cam.sy(x2, y2, z2, sy0, scale);
		out.add(ax, ay, bx - ax, by - ay, dx - ax, dy - ay, colour);
	}
}
