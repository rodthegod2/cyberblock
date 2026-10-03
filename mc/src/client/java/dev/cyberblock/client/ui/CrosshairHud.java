package dev.cyberblock.client.ui;

import dev.cyberblock.client.HostState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The crosshair in the on-foot third-person view (F2, section 1.11): vanilla draws it only in first person
 * (Hud.extractCrosshair checks CameraType.isFirstPerson), but Night City still aims down the screen centre.
 */
final class CrosshairHud {
	private static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");

	private CrosshairHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		HostState.Pose pose = HostState.frame();
		if (UiHud.player() == null || pose == null || pose.firstPerson() || HostState.vehicle() || minecraft.options.getCameraType().isFirstPerson()) {
			return;
		}

		graphics.nextStratum();
		graphics.blitSprite(RenderPipelines.CROSSHAIR, CROSSHAIR, (graphics.guiWidth() - 15) / 2, (graphics.guiHeight() - 15) / 2, 15, 15);
	}
}
