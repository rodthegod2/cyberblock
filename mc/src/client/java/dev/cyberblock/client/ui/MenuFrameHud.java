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
 * a Minecraft loading screen ({@link #drawLoading}).
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
		if (UiLink.loading()) {
			drawLoading(graphics, font, w, h);
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

	/**
	 * The loading cover as a Minecraft loading screen: vanilla's own menu background (the player's resource pack sprite, tiled at
	 * runtime) darkened, "Loading Night City" with animated dots, a strip that only says "working" (Cyberpunk does not report how far
	 * it is), and one tip line from the lang list.
	 */
	private static void drawLoading(final GuiGraphicsExtractor graphics, final Font font, final int w, final int h) {
		long now = System.nanoTime();
		int dots = LoadingCover.dots(now);
		// opaque: vanilla's menu background is see-through over a world
		graphics.fill(0, 0, w, h, 0xFF000000);
		Screen.extractMenuBackgroundTexture(graphics, Screen.MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, w, h);
		graphics.fill(0, 0, w, h, 0x80000000);
		// the title stays put while the dots come and go: it is placed as if all three dots were there
		Component title = Component.translatableWithFallback("cyberblock.ui.loading", "Loading Night City");
		int x = (w - font.width(title) - font.width("...")) / 2;
		graphics.text(font, title, x, h / 2 - 4, McTheme.WHITE);
		graphics.text(font, ".".repeat(dots), x + font.width(title), h / 2 - 4, McTheme.WHITE);
		int bw = Math.min(160, w - 40), bx = (w - bw) / 2, by = h / 2 + 10;
		McTheme.slot(graphics, bx, by, bw, 6);
		int seg = bw / 4;
		graphics.fill(bx + 1 + dots * (bw - 2 - seg) / 3, by + 1, bx + 1 + dots * (bw - 2 - seg) / 3 + seg, by + 5, McTheme.GREEN);
		Component tip = LoadingCover.tip(LoadingCover.since(now), now);
		if (tip != null) {
			int ty = by + 24;
			for (var line : font.split(tip, Math.min(260, w - 40))) {
				graphics.centeredText(font, line, w / 2, ty, McTheme.GRAY);
				ty += font.lineHeight + 2;
			}
		}
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
