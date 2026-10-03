package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The radar turns with the host camera: straight ahead is up, right is right (plan 2026-10-03-retest3-polish W2). */
class RadarProjectionTest {
	private static final double EPS = 1e-4;

	@Test
	void facingSouthAheadIsUp() {
		// yaw 0 faces +Z: a point 10 blocks south is straight up, west (-X) is to the right
		RadarProjection.Point ahead = RadarProjection.project(0, 10, 0.0F, 1.5, 36);
		assertEquals(0.0, ahead.x(), EPS);
		assertEquals(-15.0, ahead.y(), EPS);
		assertTrue(ahead.inside());
		RadarProjection.Point west = RadarProjection.project(-10, 0, 0.0F, 1.5, 36);
		assertEquals(15.0, west.x(), EPS);
		assertEquals(0.0, west.y(), EPS);
	}

	@Test
	void facingWestNorthIsRight() {
		RadarProjection.Point ahead = RadarProjection.project(-8, 0, 90.0F, 2.0, 36);
		assertEquals(0.0, ahead.x(), EPS);
		assertEquals(-16.0, ahead.y(), EPS);
		RadarProjection.Point north = RadarProjection.project(0, -8, 90.0F, 2.0, 36);
		assertEquals(16.0, north.x(), EPS);
		assertEquals(0.0, north.y(), EPS);
		RadarProjection.Point behind = RadarProjection.project(8, 0, 90.0F, 2.0, 36);
		assertEquals(16.0, behind.y(), EPS);
	}

	@Test
	void outsideAndEdgeClamp() {
		RadarProjection.Point far = RadarProjection.project(0, 100, 0.0F, 1.5, 36);
		assertFalse(far.inside());
		RadarProjection.Point edge = RadarProjection.clampToEdge(far, 36);
		assertEquals(0.0, edge.x(), EPS);
		assertEquals(-36.0, edge.y(), EPS);
		// a diagonal keeps its direction and touches the square
		RadarProjection.Point diag = RadarProjection.clampToEdge(new RadarProjection.Point(100, 50, false), 36);
		assertEquals(36.0, diag.x(), EPS);
		assertEquals(18.0, diag.y(), EPS);
		RadarProjection.Point in = new RadarProjection.Point(3, 4, true);
		assertSame(in, RadarProjection.clampToEdge(in, 36));
	}

	@Test
	void bearing() {
		assertEquals(0.0, RadarProjection.bearing(0, 5, 0.0F), EPS);
		assertEquals(90.0, RadarProjection.bearing(-5, 0, 0.0F), EPS);
		assertEquals(-90.0, RadarProjection.bearing(5, 0, 0.0F), EPS);
		assertEquals(180.0, RadarProjection.bearing(0, -5, 0.0F), EPS);
		assertEquals(0.0, RadarProjection.bearing(0, -5, 180.0F), EPS);
	}

	/** The rotated world grid lands exactly where project() puts each point, whatever the yaw. */
	@ParameterizedTest
	@ValueSource(floats = {0.0F, 37.5F, 90.0F, -135.0F, 180.0F, 271.0F})
	void gridRotationMatchesProject(final float yaw) {
		double scale = 1.5;
		Matrix3x2f pose = new Matrix3x2f().rotate(RadarProjection.gridRotation(yaw));
		double[][] offsets = {{3, 7}, {-12, 4}, {0, -20}, {9, 9}};
		for (double[] d : offsets) {
			Vector2f local = new Vector2f((float) (-d[0] * scale), (float) (-d[1] * scale));
			pose.transformPosition(local);
			RadarProjection.Point p = RadarProjection.project(d[0], d[1], yaw, scale, 36);
			assertEquals(p.x(), local.x, 1e-3, "x for " + d[0] + "," + d[1]);
			assertEquals(p.y(), local.y, 1e-3, "y for " + d[0] + "," + d[1]);
		}
	}
}
