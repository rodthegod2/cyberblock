package dev.cyberblock.client.mode.pvphack;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.hack.breach.BreachScreen;
import dev.cyberblock.mode.pvphack.PvpHackWire;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * "Intrusion detected" (megaplan 8-X1): a red panel at the top of the screen while a player's hack uploads into you, naming the hacker
 * and the chip, with what you can do (the Breach that opened, or nothing but brace) and a bar running down to the upload's end. While
 * the Breach screen is open the panel is compact (no hint line) so it stays above the puzzle. As wide as its text.
 */
final class IntrusionHud {
	static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "pvphack_intrusion");
	private static final int RED = 0xFFFF3B3B;
	private static final int PANEL = 0xD0140608;
	private static final int MIN_WIDTH = 160;
	private static final int PAD = 8;

	private IntrusionHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		PvpHackWire.Msg m = PvpHackClient.intrusion();
		if (m == null || m.hack() == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		boolean compact = minecraft.gui.screen() instanceof BreachScreen;
		Component title = Component.translatableWithFallback("cyberblock.pvphack.intrusion", "INTRUSION DETECTED");
		Component what = Component.translatableWithFallback("cyberblock.pvphack.intrusion.from", "%s is uploading %s", m.from(),
			Component.translatable("hack.cyberblock." + m.hack().wire()));
		Component hint = m.breach() ? Component.translatableWithFallback("cyberblock.pvphack.intrusion.breach", "Solve the Breach to block it")
			: Component.translatableWithFallback("cyberblock.pvphack.intrusion.brace", "No Breach possible: brace for it");
		int text = Math.max(font.width(title), Math.max(font.width(what), compact ? 0 : font.width(hint)));
		int width = Math.min(graphics.guiWidth() - 4, Math.max(MIN_WIDTH, text + 2 * PAD));
		int cx = graphics.guiWidth() / 2;
		int x0 = cx - width / 2, x1 = x0 + width;
		int y0 = compact ? 2 : 8, y1 = y0 + (compact ? 30 : 44);
		boolean blink = Util.getMillis() / 250L % 2L == 0L;
		graphics.fill(x0, y0, x1, y1, PANEL);
		graphics.fill(x0, y0, x1, y0 + 1, RED);
		graphics.fill(x0, y1 - 1, x1, y1, RED);
		graphics.fill(x0, y0, x0 + 1, y1, RED);
		graphics.fill(x1 - 1, y0, x1, y1, RED);
		graphics.centeredText(font, title, cx, y0 + 4, blink ? RED : 0xFFFFFFFF);
		graphics.centeredText(font, what, cx, y0 + 15, 0xFFE0E0E0);
		if (!compact) {
			graphics.centeredText(font, hint, cx, y0 + 26, 0xFF5FF4FF);
		}

		int bar = Math.round((width - 8) * PvpHackClient.intrusionLeft());
		graphics.fill(x0 + 4, y1 - 5, x1 - 4, y1 - 3, 0xFF3A1010);
		graphics.fill(x0 + 4, y1 - 5, x0 + 4 + bar, y1 - 3, RED);
	}
}
