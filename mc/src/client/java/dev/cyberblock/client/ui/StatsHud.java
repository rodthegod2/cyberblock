package dev.cyberblock.client.ui;

import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Under the radar (section 1.11): eddies with a gold-nugget icon, the wanted level as five pixel stars, the Night City clock and
 * district, and km/h in a vehicle. Text in Minecraft's font with its drop shadow.
 */
final class StatsHud {
	private static final int LINE = 10;
	private static final ItemStack NUGGET = new ItemStack(Items.GOLD_NUGGET);
	/** An original 7x7 pixel star (rows top to bottom). */
	private static final String[] STAR = {
		"...#...",
		"..###..",
		"#######",
		".#####.",
		"..###..",
		".##.##.",
		"##...##"
	};

	private StatsHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		UiModel.Stats stats = UiModel.stats;
		if (stats == null || UiHud.player() == null) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		int x = RadarHud.MARGIN, y = RadarHud.MARGIN + RadarHud.SIZE + 3;
		// €$ 12,500 beside a gold nugget drawn at 3/4 size
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y - 2);
		graphics.pose().scale(0.75F, 0.75F);
		graphics.item(NUGGET, 0, 0);
		graphics.pose().popMatrix();
		Component money = Component.translatableWithFallback("cyberblock.ui.money", "€$ %s", String.format(Locale.ROOT, "%,d", stats.money()));
		graphics.text(font, money, x + 14, y + 1, McTheme.GOLD, true);
		y += LINE + 2;

		if (stats.wanted() > 0) {
			for (int i = 0; i < 5; i++) {
				star(graphics, x + i * 9, y, i < stats.wanted());
			}

			y += LINE;
		}

		Component time = Component.translatableWithFallback("cyberblock.ui.time", "%s:%s",
			String.format(Locale.ROOT, "%02d", stats.hour()), String.format(Locale.ROOT, "%02d", stats.minute()));
		graphics.text(font, time, x, y, McTheme.WHITE, true);
		if (!stats.district().isEmpty()) {
			int tx = x + font.width(time) + 6;
			graphics.text(font, font.plainSubstrByWidth(stats.district(), RadarHud.SIZE + 60 - (tx - x)), tx, y, McTheme.GRAY, true);
		}

		y += LINE;
		if (stats.inVehicle()) {
			Component speed = Component.translatableWithFallback("cyberblock.ui.speed", "%s km/h", Math.round(stats.speed()));
			graphics.text(font, speed, x, y, McTheme.YELLOW, true);
		}
	}

	/** A wanted star: package A's sprite when there is one, else the original pixel star; red when lit, dark grey when not. */
	private static void star(final GuiGraphicsExtractor graphics, final int x, final int y, final boolean lit) {
		if (UiHud.icon(graphics, lit ? "wanted_star" : "wanted_star_empty", x, y, 7, 7, 0xFFFFFFFF)) {
			return;
		}

		int colour = lit ? McTheme.RED : 0xFF3F3F3F;
		for (int row = 0; row < STAR.length; row++) {
			for (int col = 0; col < STAR[row].length(); col++) {
				if (STAR[row].charAt(col) == '#') {
					graphics.fill(x + col + 1, y + row + 1, x + col + 2, y + row + 2, 0xFF000000);
				}
			}
		}

		for (int row = 0; row < STAR.length; row++) {
			for (int col = 0; col < STAR[row].length(); col++) {
				if (STAR[row].charAt(col) == '#') {
					graphics.fill(x + col, y + row, x + col + 1, y + row + 1, colour);
				}
			}
		}
	}
}
