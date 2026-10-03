package dev.cyberblock.client.ui.screen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The Atlas's pan and zoom (plan 2026-10-03-retest3-polish W5). */
class AtlasProjectionTest {
	private static final double EPS = 1e-9;

	@Test
	void northIsUpAndCentreIsTheViewCentre() {
		AtlasView view = new AtlasView(100, -50, 0.5);
		assertEquals(200.0, view.screenX(100, 200), EPS);
		assertEquals(120.0, view.screenY(-50, 120), EPS);
		// 64 blocks east is 32 px right, 64 blocks north (-z) is 32 px up
		assertEquals(232.0, view.screenX(164, 200), EPS);
		assertEquals(88.0, view.screenY(-114, 120), EPS);
	}

	@Test
	void screenAndWorldRoundTrip() {
		AtlasView view = new AtlasView(-1234.5, 987.25, 1.7);
		double sx = view.screenX(-1200, 213), sy = view.screenY(1000, 117);
		assertEquals(-1200.0, view.worldX(sx, 213), 1e-6);
		assertEquals(1000.0, view.worldZ(sy, 117), 1e-6);
	}

	@Test
	void panFollowsTheCursor() {
		AtlasView view = new AtlasView(0, 0, 2.0);
		double before = view.screenX(10, 100);
		view.pan(30, -20);
		assertEquals(before + 30, view.screenX(10, 100), EPS);
		assertEquals(view.screenY(10, 100), 100 + (10 - view.centreZ()) * 2.0, EPS);
		assertEquals(-15.0, view.centreX(), EPS);
		assertEquals(10.0, view.centreZ(), EPS);
	}

	@Test
	void zoomKeepsThePointUnderTheCursor() {
		AtlasView view = new AtlasView(50, 50, 1.0);
		double wx = view.worldX(300, 200), wz = view.worldZ(40, 120);
		view.zoomAt(2.0, 300, 40, 200, 120);
		assertEquals(2.0, view.zoom(), EPS);
		assertEquals(300.0, view.screenX(wx, 200), 1e-6);
		assertEquals(40.0, view.screenY(wz, 120), 1e-6);
	}

	@Test
	void zoomIsClamped() {
		AtlasView view = new AtlasView(0, 0, 100.0);
		assertEquals(AtlasView.MAX_ZOOM, view.zoom(), EPS);
		view.zoomAt(1e-9, 0, 0, 0, 0);
		assertEquals(AtlasView.MIN_ZOOM, view.zoom(), EPS);
	}

	@Test
	void gridStepKeepsLinesApart() {
		assertEquals(16, new AtlasView(0, 0, 2.0).gridStep());
		assertEquals(64, new AtlasView(0, 0, 0.5).gridStep());
		assertEquals(512, new AtlasView(0, 0, 0.05).gridStep());
		AtlasView view = new AtlasView(0, 0, 0.3);
		assertTrue(view.gridStep() * view.zoom() >= 24.0);
	}
}
