package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.mode.downed.DownedWire;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Downed HUD (megaplan 8-T2): while this player is down and crawling (the downed screen closed), a red edge, "DOWN", the
 * bleed-out or Trauma Team countdown, a teammate's revive bar, and how to get the screen back; while this player revives someone, a
 * progress bar under the crosshair ("Reviving <name>"), shown while the server's updates keep coming. HUD elements, so a host's
 * Cyberpunk picture gets them too.
 */
public final class DownedHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/downed");
	public static final Identifier REVIVE_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/revive");
	/** This player's own status (client thread). */
	public static volatile DownedWire.Status status = DownedWire.Status.UP;
	/** When the status arrived (System.currentTimeMillis), so the countdowns run between updates. */
	public static volatile long statusAt;
	/** The revive this player is doing, and when the server last confirmed it. */
	public static volatile DownedWire.Revive revive;
	public static volatile long reviveAt;
	/** A revive bar fades this long after the last update (the server sends one per held use, every 4 ticks). */
	static final long REVIVE_SHOWN_MS = 600;
	static final int BAR_W = 120, BAR_H = 5;

	private DownedHud() {
	}

	/** Whole seconds left of a countdown the server gave {@code ageMs} ago. */
	public static int left(final int seconds, final long ageMs) {
		return Math.max(0, seconds - (int) (ageMs / 1000));
	}

	public static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		DownedWire.Status s = status;
		Minecraft minecraft = Minecraft.getInstance();
		if (!s.down() || minecraft.player == null || minecraft.gui.screen() != null || UiHud.covered()) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		// a pulsing red edge
		int a = 0x30 + (int) (0x30 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0)));
		int edge = a << 24 | 0xAA0000;
		graphics.fillGradient(0, 0, w, h / 6, edge, 0x00AA0000);
		graphics.fillGradient(0, h - h / 6, w, h, 0x00AA0000, edge);
		Font font = minecraft.font;
		long age = System.currentTimeMillis() - statusAt;
		int y = h / 2 + 18;
		graphics.centeredText(font, Component.translatableWithFallback("cyberblock.downed.hud.title", "DOWN"), w / 2, y, McTheme.RED);
		y += 11;
		Component line = s.trauma() >= 0
			? Component.translatableWithFallback("cyberblock.downed.hud.trauma", "Trauma Team lands in %s s", left(s.trauma(), age))
			: Component.translatableWithFallback("cyberblock.downed.hud.bleed", "Bleeding out in %s s", left(s.left(), age));
		graphics.centeredText(font, line, w / 2, y, s.trauma() >= 0 ? McTheme.AQUA : McTheme.WHITE);
		y += 11;
		if (s.revive() > 0.0F && !s.by().isEmpty()) {
			graphics.centeredText(font, Component.translatableWithFallback("cyberblock.downed.hud.reviving_you", "%s is reviving you", s.by()), w / 2, y,
				McTheme.GREEN);
			bar(graphics, w / 2 - BAR_W / 2, y + 10, s.revive(), McTheme.GREEN);
			y += 18;
		}

		graphics.centeredText(font, Component.translatableWithFallback("cyberblock.downed.hud.hint", "Esc, then Downed...: Trauma Team or give up"), w / 2,
			y, McTheme.GRAY);
	}

	public static void drawRevive(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		DownedWire.Revive r = revive;
		Minecraft minecraft = Minecraft.getInstance();
		if (r == null || System.currentTimeMillis() - reviveAt > REVIVE_SHOWN_MS || minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		Component label = Component.translatableWithFallback("cyberblock.downed.hud.reviving", "Reviving %s: %s",
			r.name(), String.format(Locale.ROOT, "%d%%", Math.round(r.p() * 100)));
		graphics.centeredText(minecraft.font, label, w / 2, h / 2 + 12, McTheme.GREEN);
		bar(graphics, w / 2 - BAR_W / 2, h / 2 + 23, r.p(), McTheme.GREEN);
	}

	static void bar(final GuiGraphicsExtractor graphics, final int x, final int y, final float p, final int colour) {
		graphics.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xFF000000);
		graphics.fill(x, y, x + BAR_W, y + BAR_H, 0xFF3A3A3A);
		graphics.fill(x, y, x + Math.round(BAR_W * Math.clamp(p, 0.0F, 1.0F)), y + BAR_H, colour);
	}
}
