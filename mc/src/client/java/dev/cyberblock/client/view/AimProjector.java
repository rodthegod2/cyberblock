package dev.cyberblock.client.view;

import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Q-V3: where a world point shows on screen through the host camera, for the third-person crosshair. Minecraft's camera is the
 * host's (CameraMixin: position, rotation with roll, field of view), so its view-rotation-projection matrix projects exactly what
 * the frame shows.
 */
public final class AimProjector {
	/** Points closer to the camera plane than this (clip w) count as behind it. */
	private static final float MIN_W = 1.0E-4F;

	private AimProjector() {
	}

	/**
	 * The GUI position {x, y} of a point given relative to the camera (point minus camera position), or null when it lies behind
	 * the camera or outside the screen.
	 *
	 * @param viewProjection the camera's view rotation and projection (no translation: Camera.getViewRotationProjectionMatrix)
	 */
	public static float @Nullable [] toGui(final Matrix4fc viewProjection, final double rx, final double ry, final double rz,
		final int guiWidth, final int guiHeight) {
		Vector4f clip = viewProjection.transform(new Vector4f((float) rx, (float) ry, (float) rz, 1.0F));
		if (!(clip.w > MIN_W)) {
			return null;
		}

		float nx = clip.x / clip.w, ny = clip.y / clip.w;
		if (!(Math.abs(nx) <= 1.0F && Math.abs(ny) <= 1.0F)) {
			return null;
		}

		return new float[] {(nx + 1.0F) * 0.5F * guiWidth, (1.0F - ny) * 0.5F * guiHeight};
	}
}
