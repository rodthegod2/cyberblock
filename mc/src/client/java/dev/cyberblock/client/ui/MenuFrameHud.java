package dev.cyberblock.client.ui;

import dev.cyberblock.client.HostState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Minecraft frame around a Cyberpunk menu that is passed through (F12, section 1.11: shops, ripperdoc, stash, breach,
 * settings and the rest Minecraft does not replace). While the host camera says a menu is open, the compositor skips Minecraft's
 * world and keeps only this overlay: a container-grey title bar naming the menu and a raised bevel border round the screen;
 * every other Night City element and the vanilla HUD return early. {@code screen.pass:"loading"} draws a whole cover instead,
 * vanilla's menu background with "Loading Night City...".
 */
final class MenuFrameHud {
	private static final int BAR = 16;
	private static final int BORDER = 3;

	private MenuFrameHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		String pass = UiModel.pass;
		Font font = Minecraft.getInstance().font;
		int w = graphics.guiWidth(), h = graphics.guiHeight();
		if (pass.equals("loading") && UiLink.recentlyLinked()) {
			// opaque: vanilla's menu background is see-through over a world
			graphics.fill(0, 0, w, h, 0xFF000000);
			Screen.extractMenuBackgroundTexture(graphics, Screen.MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, w, h);
			Component label = label("loading");
			int dots = (int) (System.nanoTime() / 400_000_000L % 4);
			graphics.centeredText(font, label, w / 2, h / 2 - 4, McTheme.WHITE);
			// a vanilla-like progress strip that only says "working": Cyberpunk does not report how far it is
			int bw = Math.min(160, w - 40), bx = (w - bw) / 2, by = h / 2 + 10;
			McTheme.slot(graphics, bx, by, bw, 6);
			int seg = bw / 4;
			graphics.fill(bx + 1 + dots * (bw - 2 - seg) / 3, by + 1, bx + 1 + dots * (bw - 2 - seg) / 3 + seg, by + 5, McTheme.GREEN);
			return;
		}

		HostState.Pose pose = HostState.frame();
		if (pose == null || !pose.menu()) {
			return;
		}

		McTheme.bevelFrame(graphics, 1, 1, w - 2, h - 2, BORDER);
		McTheme.panel(graphics, w / 2 - 90, 0, 180, BAR + 2);
		Component label = label(pass.isEmpty() ? "other" : pass);
		graphics.text(font, label, (w - font.width(label)) / 2, 5, McTheme.PANEL_TEXT, false);
	}

	/** Section 1.12 pass-through labels. */
	static Component label(final String name) {
		String english = switch (name) {
			case "vendor" -> "Shop";
			case "ripperdoc" -> "Ripperdoc";
			case "stash" -> "Stash";
			case "wardrobe" -> "Wardrobe";
			case "crafting" -> "Crafting";
			case "breach" -> "Breach Protocol";
			case "arcade" -> "Arcade";
			case "braindance" -> "Braindance";
			case "tutorial" -> "Tip";
			case "settings" -> "Settings";
			case "saves" -> "Saves";
			case "death" -> "Flatlined";
			case "loading" -> "Loading Night City...";
			default -> "Night City";
		};
		return Component.translatableWithFallback("cyberblock.ui.pass." + name, english);
	}
}
