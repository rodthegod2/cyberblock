package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.mode.heat.HeatWire;
import java.util.function.BooleanSupplier;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Server Heat meter (megaplan 8-T1) in the bottom-right corner, beside the hotbar: "Heat 47 · tier 3" over a bar coloured by the
 * tier (yellow, gold, orange, red, dark red), "Top chaos: <name>" under it, and a blinking red line while Minecraft squads are after
 * this player. Drawn only while the meter is above 0 and no pass-through menu covers the screen. A HUD element, so it reaches a
 * host's Cyberpunk picture through the overlay like the rest of the HUD.
 */
public final class HeatMeterHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/heat_meter");
	static final int WIDTH = 100, BAR = 5, MARGIN = 4, LINE = 10;
	/** The meter as the server last sent it (client thread). */
	public static volatile HeatWire.Meter meter = HeatWire.Meter.NONE;
	/** True while another element shows the meter (the caster overlay sets it). */
	public static volatile BooleanSupplier hidden = () -> false;

	private HeatMeterHud() {
	}

	/** The bar colour of a tier (ARGB): yellow at 0-1 through dark red at 5. */
	static int colour(final int tier) {
		return switch (Math.clamp(tier, 0, 5)) {
			case 0, 1 -> McTheme.YELLOW;
			case 2 -> McTheme.GOLD;
			case 3 -> 0xFFFF7A1A;
			case 4 -> McTheme.RED;
			default -> 0xFFAA0000;
		};
	}

	public static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		HeatWire.Meter m = meter;
		Minecraft minecraft = Minecraft.getInstance();
		if (m.value() <= 0 || minecraft.player == null || UiHud.covered() || hidden.getAsBoolean()) {
			return;
		}

		Font font = minecraft.font;
		int right = graphics.guiWidth() - MARGIN;
		int x = right - WIDTH;
		int y = graphics.guiHeight() - MARGIN - BAR - (m.top().isEmpty() ? 0 : LINE);
		// the bar: a dark track, the fill, a 1 px outline
		graphics.fill(x - 1, y - 1, right + 1, y + BAR + 1, 0xFF000000);
		graphics.fill(x, y, right, y + BAR, 0xFF3A3A3A);
		graphics.fill(x, y, x + Math.round(WIDTH * m.value() / 100.0F), y + BAR, colour(m.tier()));
		Component label = Component.translatableWithFallback("cyberblock.heat.hud", "Heat %s · tier %s", m.value(), m.tier());
		graphics.text(font, label, right - font.width(label), y - LINE, colour(m.tier()), true);
		if (!m.top().isEmpty()) {
			Component top = Component.translatableWithFallback("cyberblock.heat.hud.top", "Top chaos: %s", m.top());
			graphics.text(font, font.plainSubstrByWidth(top.getString(), WIDTH + 40), right - Math.min(font.width(top), WIDTH + 40), y + BAR + 3, McTheme.GRAY, true);
		}

		if (m.hunted() && System.currentTimeMillis() / 500 % 2 == 0) {
			Component hunted = Component.translatableWithFallback("cyberblock.heat.hud.hunted", "NCPD is after you");
			graphics.text(font, hunted, right - font.width(hunted), y - 2 * LINE, McTheme.RED, true);
		}
	}
}
