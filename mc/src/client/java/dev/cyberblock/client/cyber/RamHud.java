package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The RAM bar: left of the experience bar, one segment per RAM point, drawn only with a cyberdeck in the inventory. It is a
 * HUD element, so it lands in the overlay layer the frame exporter hands to Cyberpunk.
 */
final class RamHud {
	static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "ram_bar");
	/** art/gui/ram_bar.png: 182x10, rows 0-4 the empty frame, rows 5-9 the filled bar. */
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/gui/ram_bar.png");
	private static final int TEX_W = 182;
	private static final int TEX_H = 10;
	private static final int ROW = 5;
	/** Drawn at half the sheet's width so it fits beside the experience bar. */
	private static final int WIDTH = 91;
	private static final int LABEL_COLOUR = 0xFF55FFFF;

	private RamHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator()) {
			return;
		}

		int max = CyberData.maxRam(player);
		if (max <= 0) {
			return;
		}

		int ram = Math.clamp(CyberData.ram(player), 0, max);
		// the experience bar is 182 wide, centred, its top 29 above the bottom edge
		int x0 = Math.max(2, (graphics.guiWidth() - 182) / 2 - 6 - WIDTH);
		int y0 = graphics.guiHeight() - 29;
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0.0F, 0.0F, WIDTH, ROW, TEX_W, ROW, TEX_W, TEX_H);
		for (int i = 0; i < ram; i++) {
			int left = x0 + i * WIDTH / max;
			int right = x0 + (i + 1) * WIDTH / max - (i < max - 1 ? 1 : 0);
			float u = (float) i * TEX_W / max;
			graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, left, y0, u, ROW, right - left, ROW, TEX_W / max, ROW, TEX_W, TEX_H);
		}

		graphics.text(minecraft.font, Component.translatable("cyberblock.ram.label", ram, max), x0, y0 - 10, LABEL_COLOUR, true);
	}
}
