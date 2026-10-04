package dev.cyberblock.client.mode.pvphack;

import dev.cyberblock.Cyberblock;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Reboot Optics on this player (megaplan 8-X1): a full-screen glitch over the HUD while vanilla Blindness darkens the world. Torn
 * horizontal slices in cyan and magenta, blocks of broken pixels, scan lines and a split-colour "OPTICS REBOOTING" with the seconds left;
 * it calms down as the effect runs out. The pattern changes every 60 ms from a seed of the clock (original, procedural; no texture). A
 * Cyberpunk-linked player also gets the ReShade glitch through the {@code glitch} DOWN (8D's pass).
 */
final class GlitchOverlay {
	static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "pvphack_glitch");
	private static final int CYAN = 0x00F0FF;
	private static final int MAGENTA = 0xFF2BD6;
	private static final int YELLOW = 0xFCEE0A;
	private static final int RED = 0xFF3B3B;
	private static final int[] PALETTE = {CYAN, MAGENTA, YELLOW, RED, 0xFFFFFF};
	private static final long FRAME_MS = 60L;

	private GlitchOverlay() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		float left = PvpHackClient.glitchLeft();
		if (left <= 0.0F) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		long frame = Util.getMillis() / FRAME_MS;
		Random r = new Random(frame * 0x9E3779B97F4A7C15L);
		// strength: full for the first half, then fading with the time left
		float strength = Math.min(1.0F, left * 2.0F);
		graphics.fill(0, 0, w, h, alpha(0.35F * strength) | 0x05000A);
		slices(graphics, r, w, h, strength);
		blocks(graphics, r, w, h, strength);
		for (int y = 0; y < h; y += 3) {
			graphics.fill(0, y, w, y + 1, alpha(0.18F * strength));
		}

		if (r.nextFloat() < 0.15F * strength) {
			graphics.fill(0, 0, w, h, alpha(0.12F) | 0xFFFFFF); // a white flicker now and then
		}

		label(graphics, Minecraft.getInstance().font, r, w, h, strength);
	}

	/** Torn rows: a band of the screen colour-shifted sideways, with a bright edge line. */
	private static void slices(final GuiGraphicsExtractor graphics, final Random r, final int w, final int h, final float strength) {
		int n = 4 + Math.round(10 * strength);
		for (int i = 0; i < n; i++) {
			int y = r.nextInt(Math.max(1, h));
			int height = 2 + r.nextInt(16);
			int shift = r.nextInt(81) - 40;
			int colour = r.nextBoolean() ? CYAN : MAGENTA;
			int x0 = Math.max(0, shift), x1 = Math.min(w, w + shift);
			graphics.fill(x0, y, x1, y + height, alpha(0.22F * strength) | colour);
			graphics.fill(x0, y, x1, y + 1, alpha(0.6F * strength) | colour);
		}
	}

	/** Broken pixel blocks in the palette, denser while the glitch is strong. */
	private static void blocks(final GuiGraphicsExtractor graphics, final Random r, final int w, final int h, final float strength) {
		int n = Math.round(50 * strength);
		for (int i = 0; i < n; i++) {
			int bw = 4 + r.nextInt(28), bh = 2 + r.nextInt(10);
			int x = r.nextInt(Math.max(1, w)), y = r.nextInt(Math.max(1, h));
			graphics.fill(x, y, x + bw, y + bh, alpha((0.25F + 0.45F * r.nextFloat()) * strength) | PALETTE[r.nextInt(PALETTE.length)]);
		}
	}

	/** The warning with a red/cyan split that jitters, and the seconds left. */
	private static void label(final GuiGraphicsExtractor graphics, final Font font, final Random r, final int w, final int h, final float strength) {
		Component word = Component.translatableWithFallback("cyberblock.pvphack.glitch", "OPTICS REBOOTING");
		int cx = w / 2, cy = h / 2 - 30;
		int jitter = Math.round(3 * strength);
		int dx = jitter == 0 ? 0 : r.nextInt(2 * jitter + 1) - jitter;
		graphics.centeredText(font, word, cx - 2 + dx, cy, 0xC0000000 | RED);
		graphics.centeredText(font, word, cx + 2 - dx, cy + 1, 0xC0000000 | CYAN);
		graphics.centeredText(font, word, cx, cy, 0xFFFFFFFF);
		String code = String.format(Locale.ROOT, "SYS.ERR 0x%04X  %.1f s", r.nextInt(0x10000), PvpHackClient.glitchMsLeft() / 1000.0);
		graphics.centeredText(font, code, cx, cy + 14, 0xFF000000 | YELLOW);
	}

	/** A 0..1 alpha as the top byte of an ARGB colour. */
	private static int alpha(final float a) {
		return Math.round(Math.clamp(a, 0.0F, 1.0F) * 255.0F) << 24;
	}
}
