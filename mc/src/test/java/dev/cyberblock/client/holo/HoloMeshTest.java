package dev.cyberblock.client.holo;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.atlas.AtlasTiles;
import dev.cyberblock.holo.HoloWire;
import java.util.List;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;

/** Megaplan 7-T1: the Holo-Table's camera and its miniature's geometry (pure). */
class HoloMeshTest {
	private static final double EPS = 1e-9;

	@Test
	void topDownWithoutTurnIsTheAtlas() {
		HoloCamera cam = new HoloCamera(100, 200, 64);
		cam.setTilt(Math.toRadians(90));
		cam.setYaw(0);
		cam.setBase(60);
		// north up, east right, heights do not move a point
		assertEquals(110, cam.sx(110, 200, 100, 1.0), EPS);
		assertEquals(90, cam.sy(100, 500, 190, 100, 1.0), 1e-6);
		assertTrue(cam.faces(0, 1), "the south faces look at the viewer");
		assertFalse(cam.faces(0, -1));
		assertFalse(cam.faces(1, 0) || cam.faces(-1, 0));
	}

	@Test
	void tiltingRaisesHighThingsAndTurningTurns() {
		HoloCamera cam = new HoloCamera(0, 0, 64);
		cam.setTilt(Math.toRadians(45));
		cam.setBase(0);
		assertTrue(cam.sy(0, 10, 0, 0, 1) < cam.sy(0, 0, 0, 0, 1), "higher = further up the screen");
		cam.setYaw(Math.PI / 2);
		// a quarter turn: east (+x) now points toward the viewer, north (-z) to the right
		assertEquals(10, cam.sx(0, -10, 0, 1), EPS);
		assertTrue(cam.depth(10, 0) > cam.depth(-10, 0));
		assertTrue(cam.faces(1, 0));
	}

	@Test
	void theCameraKeepsItsLimits() {
		HoloCamera cam = new HoloCamera(0, 0, 10);
		assertEquals(HoloCamera.MIN_RADIUS, cam.radius(), "radius clamped up");
		cam.zoom(1e-6);
		assertEquals(HoloCamera.MAX_RADIUS, cam.radius());
		cam.tiltBy(1e6);
		assertEquals(HoloCamera.MAX_TILT, cam.tilt(), EPS);
		cam.tiltBy(-1e6);
		assertEquals(HoloCamera.MIN_TILT, cam.tilt(), EPS);
		cam.turn(-1);
		assertTrue(cam.yaw() >= 0 && cam.yaw() < 2 * Math.PI);
	}

	@Test
	void panningMovesTheGroundWithTheCursor() {
		HoloCamera cam = new HoloCamera(0, 0, 64);
		cam.setTilt(Math.toRadians(90));
		cam.setYaw(Math.toRadians(30));
		double before = cam.sx(5, 7, 0, 2.0), beforeY = cam.sy(5, 0, 7, 0, 2.0);
		cam.pan(12, -8, 2.0);
		assertEquals(before + 12, cam.sx(5, 7, 0, 2.0), 1e-6);
		assertEquals(beforeY - 8, cam.sy(5, 0, 7, 0, 2.0), 1e-6);
	}

	/** A flat street at y 60 with one 30-block tower around block (8, 8); nothing known west of x = -40. */
	private static HoloMesh.Cells city() {
		return (cx, cz) -> {
			if (cx * AtlasTiles.CELL < -40) {
				return 0;
			}

			boolean tower = cx * AtlasTiles.CELL >= 6 && cx * AtlasTiles.CELL < 12 && cz * AtlasTiles.CELL >= 6 && cz * AtlasTiles.CELL < 12;
			return AtlasTiles.encode(tower ? 90 : 60, false);
		};
	}

	@Test
	void theGridTakesTheHighestSampleAndKnowsWhatIsUnknown() {
		HoloMesh.Grid g = HoloMesh.build(16, 0, 0, 64, city(), List.of());
		assertEquals(8.0, g.step(), EPS);
		assertEquals(-64, g.x0(), EPS);
		assertEquals(60.0F, g.base());
		// cell (9, 9) covers blocks 8..16: it holds the tower
		assertEquals(90.0F, g.heights()[9 * 16 + 9]);
		assertEquals(60.0F, g.heights()[0 * 16 + 12]);
		assertTrue(Float.isNaN(g.heights()[0]), "x < -40 is unknown");
		assertEquals(0, g.colours()[0]);
		assertEquals(0xFF, g.colours()[9 * 16 + 9] >>> 24, "known cells are opaque");
	}

	@Test
	void theGridIsAlignedToItsStep() {
		HoloMesh.Grid a = HoloMesh.build(16, 3, 5, 64, city(), List.of());
		HoloMesh.Grid b = HoloMesh.build(16, 4, 6, 64, city(), List.of());
		assertEquals(a.x0(), b.x0(), EPS, "a small pan does not move the cells");
		assertEquals(0, a.x0() % a.step(), EPS);
	}

	@Test
	void detailNearPlayersWinsWithItsMapColour() {
		int n = 3;
		short[] h = new short[n * n];
		byte[] c = new byte[n * n];
		java.util.Arrays.fill(h, (short) 75);
		java.util.Arrays.fill(c, (byte) MapColor.COLOR_RED.id);
		HoloWire.Detail d = new HoloWire.Detail(-1, -1, n, h, c);
		HoloMesh.Grid g = HoloMesh.build(64, 0, 0, 48, city(), List.of(d));
		int i = 32 * 64 + 32; // the cell holding block (0, 0)
		assertEquals(75.0F, g.heights()[i]);
		int red = HoloMesh.tint(MapColor.COLOR_RED.calculateARGBColor(MapColor.Brightness.NORMAL));
		int redHigh = HoloMesh.tint(MapColor.COLOR_RED.calculateARGBColor(MapColor.Brightness.HIGH));
		assertTrue(g.colours()[i] == red || g.colours()[i] == redHigh, "the replica's own colour");
	}

	@Test
	void facesComeBackToFrontWithOneWinding() {
		HoloMesh.Grid g = HoloMesh.build(16, 0, 0, 64, city(), List.of());
		HoloCamera cam = new HoloCamera(0, 0, 64);
		cam.setTilt(Math.toRadians(45));
		cam.setYaw(0);
		cam.setBase(g.base());
		HoloMesh.Quads q = HoloMesh.quads(g, cam, 200, 150, 2.0, new HoloMesh.Quads());
		assertTrue(q.count() > 16 * 12, "the plate, the tops and the tower's sides");
		float[] d = q.data();
		double lastY = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < q.count(); k++) {
			int i = k * HoloMesh.FLOATS;
			assertTrue(d[i + 2] * d[i + 5] - d[i + 3] * d[i + 4] > 0, "u x v > 0 for quad " + k);
		}

		// the first quad is the plate: the whole window
		assertEquals(0xFF0B1E2A, Float.floatToRawIntBits(d[6]));
		assertEquals(16 * 16 - 16 * 3, tops(q).length, "one top per known cell (x < -40 unknown: 3 columns)");
		// facing north (yaw 0) over flat ground, the tops go from the north row to the south row
		HoloMesh.Grid flat = HoloMesh.build(16, 0, 0, 64, (cx, cz) -> AtlasTiles.encode(60, false), List.of());
		HoloMesh.Quads f = HoloMesh.quads(flat, cam, 200, 150, 2.0, new HoloMesh.Quads());
		for (float y : tops(f)) {
			assertTrue(y >= lastY - 1e-3, "back to front");
			lastY = y;
		}
	}

	/** The screen y of every top face (u horizontal and v vertical on screen at yaw 0), in drawing order. */
	private static float[] tops(final HoloMesh.Quads q) {
		float[] d = q.data(), out = new float[q.count()];
		int n = 0;
		for (int k = 1; k < q.count(); k++) {
			int i = k * HoloMesh.FLOATS;
			if (Math.abs(d[i + 3]) < 1e-3 && Math.abs(d[i + 4]) < 1e-3) {
				out[n++] = d[i + 1];
			}
		}

		return java.util.Arrays.copyOf(out, n);
	}

	@Test
	void onlyTheSidesFacingTheViewerAreDrawn() {
		HoloMesh.Cells tower = (cx, cz) -> AtlasTiles.encode(cx == 0 && cz == 0 ? 80 : 60, false);
		HoloMesh.Grid g = HoloMesh.build(16, 0, 0, 16, tower, List.of());
		HoloCamera cam = new HoloCamera(0, 0, 16);
		cam.setTilt(Math.toRadians(60));
		cam.setYaw(0);
		cam.setBase(g.base());
		HoloMesh.Quads q = HoloMesh.quads(g, cam, 0, 0, 4.0, new HoloMesh.Quads());
		// plate + 256 tops + the tower cell's south face (its west/east/north are hidden or edge-on) + the window's southern rim
		int sides = q.count() - 1 - 256;
		assertTrue(sides >= 1, "the tower's south side");
		assertTrue(sides <= 1 + 16, "no north sides, no east/west sides at yaw 0");
	}

	@Test
	void tintAndShadeKeepOpacity() {
		assertEquals(0xFF, HoloMesh.tint(0x00102030) >>> 24);
		assertEquals(0x80000000 | 0x404040, HoloMesh.shade(0x80808080, 0.5F));
	}
}
