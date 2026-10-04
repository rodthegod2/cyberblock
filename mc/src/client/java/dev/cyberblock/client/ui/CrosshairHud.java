package dev.cyberblock.client.ui;

import dev.cyberblock.client.HostState;
import dev.cyberblock.client.view.AimProjector;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The crosshair in the on-foot third-person view (F2, section 1.11): vanilla draws it only in first person
 * (Hud.extractCrosshair checks CameraType.isFirstPerson). Q-V3: it sits where Minecraft's pick meets the target (blocks along the
 * host camera's ray, EntityPickMixin; entities along Steve's eye ray), projected through the host camera, so what it covers is what
 * gets hit; with nothing within reach it is the screen centre.
 */
final class CrosshairHud {
	private static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");
	private static final int SIZE = 15;

	private CrosshairHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		HostState.Pose pose = HostState.frame();
		if (UiHud.player() == null || pose == null || pose.firstPerson() || HostState.vehicle() || minecraft.options.getCameraType().isFirstPerson()) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		float x = w / 2.0F, y = h / 2.0F;
		HitResult hit = minecraft.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			Camera camera = minecraft.gameRenderer.mainCamera();
			Vec3 rel = hit.getLocation().subtract(camera.position());
			float[] at = AimProjector.toGui(camera.getViewRotationProjectionMatrix(new Matrix4f()), rel.x, rel.y, rel.z, w, h);
			if (at != null) {
				x = at[0];
				y = at[1];
			}
		}

		graphics.nextStratum();
		// floor: at the centre this is vanilla's (guiWidth - 15) / 2
		graphics.blitSprite(RenderPipelines.CROSSHAIR, CROSSHAIR, (int) Math.floor(x - SIZE / 2.0F), (int) Math.floor(y - SIZE / 2.0F), SIZE, SIZE);
	}
}
