package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

/**
 * Megaplan Q-V3: the third-person crosshair's projection. The matrix is built the way Minecraft's Camera builds it with
 * CameraMixin's rotation: projection times the conjugate of rotationYXZ(pi - yaw, -pitch, roll).
 */
class AimProjectorTest {
	private static final float DEG = (float) (Math.PI / 180.0);
	private static final int W = 480, H = 270;

	private static Matrix4f camera(final float yaw, final float pitch, final float roll, final float fov) {
		Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.PI - yaw * DEG, -pitch * DEG, roll * DEG);
		Matrix4f view = new Matrix4f().rotation(rotation.conjugate(new Quaternionf()));
		return new Matrix4f().perspective(fov * DEG, (float) W / H, 0.05F, 1024.0F).mul(view);
	}

	@Test
	void aPointStraightAheadIsTheScreenCentre() {
		// Minecraft yaw 0 looks along +Z
		float[] at = AimProjector.toGui(camera(0.0F, 0.0F, 0.0F, 70.0F), 0.0, 0.0, 5.0, W, H);
		assertNotNull(at);
		assertEquals(W / 2.0F, at[0], 1e-3);
		assertEquals(H / 2.0F, at[1], 1e-3);
		// yaw 90 looks along -X
		float[] west = AimProjector.toGui(camera(90.0F, 0.0F, 0.0F, 70.0F), -8.0, 0.0, 0.0, W, H);
		assertNotNull(west);
		assertEquals(W / 2.0F, west[0], 1e-3);
		assertEquals(H / 2.0F, west[1], 1e-3);
	}

	@Test
	void rightIsRightAndUpIsUp() {
		Matrix4f m = camera(0.0F, 0.0F, 0.0F, 70.0F);
		// looking along +Z, -X is on the right
		float[] right = AimProjector.toGui(m, -1.0, 0.0, 5.0, W, H);
		float[] up = AimProjector.toGui(m, 0.0, 1.0, 5.0, W, H);
		assertTrue(right[0] > W / 2.0F + 10.0F);
		assertEquals(H / 2.0F, right[1], 1e-3);
		assertTrue(up[1] < H / 2.0F - 10.0F, "GUI y grows downwards");
		assertEquals(W / 2.0F, up[0], 1e-3);
	}

	@Test
	void theOffsetMatchesTheFieldOfView() {
		// a point at the top edge of a 70 degree vertical field of view lands on the top edge
		double tan = Math.tan(35.0 * DEG);
		float[] edge = AimProjector.toGui(camera(0.0F, 0.0F, 0.0F, 70.0F), 0.0, 10.0 * tan * 0.999, 10.0, W, H);
		assertNotNull(edge);
		assertEquals(0.0F, edge[1], 0.5F);
	}

	@Test
	void pitchAndRollAreFollowed() {
		// pitched 30 degrees down (Minecraft: positive pitch looks down): a point 30 degrees below the horizon is the centre
		double d = 6.0;
		float[] down = AimProjector.toGui(camera(0.0F, 30.0F, 0.0F, 70.0F), 0.0, -d * Math.sin(30.0 * DEG), d * Math.cos(30.0 * DEG), W, H);
		assertEquals(W / 2.0F, down[0], 1e-2);
		assertEquals(H / 2.0F, down[1], 1e-2);
		// rolled 90 degrees: a point above the camera shows beside the centre, level with it
		float[] rolled = AimProjector.toGui(camera(0.0F, 0.0F, 90.0F, 70.0F), 0.0, 0.5, 5.0, W, H);
		assertEquals(H / 2.0F, rolled[1], 1e-2);
		assertNotEquals(W / 2.0F, rolled[0], 5.0F);
	}

	@Test
	void behindOrOffScreenIsNull() {
		Matrix4f m = camera(0.0F, 0.0F, 0.0F, 70.0F);
		assertNull(AimProjector.toGui(m, 0.0, 0.0, -5.0, W, H), "behind the camera");
		assertNull(AimProjector.toGui(m, 0.0, 0.0, 0.0, W, H), "at the camera");
		assertNull(AimProjector.toGui(m, 50.0, 0.0, 1.0, W, H), "far to the side");
		assertNull(AimProjector.toGui(m, Double.NaN, 0.0, 5.0, W, H), "not a number");
	}
}
