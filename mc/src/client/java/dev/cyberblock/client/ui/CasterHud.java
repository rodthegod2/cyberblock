package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.mode.spectate.CasterWire;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The caster overlay (megaplan 8-T3), drawn for a spectator while the server sends it: the match title, timer and Server Heat at the
 * top, the team lists on the left (downed players in red), the kill feed on the right (fading after 20 s), the scoreboard rows at
 * the bottom left and the Holo-Table pins the spectator may see on a north-up map at the bottom right. Minecraft's font with its
 * shadow, ChatFormatting colours ({@link McTheme}).
 */
public final class CasterHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "ui/caster");
	/** The overlay as the server last sent it (client thread). */
	public static volatile CasterWire.View view = CasterWire.View.OFF;
	static final int MARGIN = 4, LINE = 10, FEED_FADE_S = 20;
	/** Below vanilla's two rows of status effect icons (y 1 and 27, 24 px each) at the top right. */
	static final int FEED_TOP = 54;
	/** The pins map's side (gui pixels), and how many of the nearest pins get their name. */
	static final int MAP = 96, NAMED = 4;

	private CasterHud() {
	}

	/** Is the overlay on (the Heat meter then lives in it)? */
	public static boolean active() {
		Minecraft minecraft = Minecraft.getInstance();
		return view.on() && minecraft.player != null && minecraft.player.isSpectator();
	}

	/** "m:ss" for a timer, "" for none. */
	public static String clock(final int seconds) {
		return seconds < 0 ? "" : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	/** One kill feed line in words. */
	public static Component line(final CasterWire.Feed f) {
		return switch (f.verb()) {
			case "killed" -> Component.translatableWithFallback("cyberblock.caster.feed.killed", "%s killed %s", f.who(), f.whom());
			case "downed" -> f.who().isEmpty() ? Component.translatableWithFallback("cyberblock.caster.feed.went_down", "%s went down", f.whom())
				: Component.translatableWithFallback("cyberblock.caster.feed.downed", "%s downed %s", f.who(), f.whom());
			case "revived" -> Component.translatableWithFallback("cyberblock.caster.feed.revived", "%s revived %s", f.who(), f.whom());
			case "bled_out" -> Component.translatableWithFallback("cyberblock.caster.feed.bled_out", "%s bled out", f.whom());
			case "trauma" -> Component.translatableWithFallback("cyberblock.caster.feed.trauma", "Trauma Team picked up %s", f.whom());
			case "died" -> Component.translatableWithFallback("cyberblock.caster.feed.died", "%s died", f.whom());
			default -> Component.literal((f.who() + " " + f.verb().replace('_', ' ') + " " + f.whom()).trim());
		};
	}

	/** The map's half-width in blocks: the farthest pin, rounded up to 32, between 32 and 1024. */
	static double range(final List<CasterWire.Pin> pins, final double x, final double z) {
		double far = 0.0;
		for (CasterWire.Pin p : pins) {
			far = Math.max(far, Math.max(Math.abs(p.x() - x), Math.abs(p.z() - z)));
		}

		return Math.clamp(Math.ceil(far / 32.0) * 32.0, 32.0, 1024.0);
	}

	/** The pins around ({@code x}, {@code z}) in a square at ({@code left}, {@code top}); a pin beyond the range sits on the edge. */
	private static void map(final GuiGraphicsExtractor graphics, final Font font, final List<CasterWire.Pin> pins, final int left, final int top,
		final double x, final double z) {
		int mid = MAP / 2;
		double range = range(pins, x, z);
		graphics.fill(left - 1, top - 1, left + MAP + 1, top + MAP + 1, 0xFF000000);
		graphics.fill(left, top, left + MAP, top + MAP, 0xB0101820);
		graphics.text(font, Component.translatableWithFallback("cyberblock.caster.map", "Pins %s · %s m", pins.size(), (int) range), left,
			top - LINE, McTheme.GOLD, true);
		graphics.fill(left + mid - 1, top + mid - 1, left + mid + 2, top + mid + 2, McTheme.WHITE);
		for (int i = 0; i < pins.size(); i++) {
			CasterWire.Pin p = pins.get(i);
			int px = left + mid + (int) Math.round(Math.clamp((p.x() - x) / range, -1.0, 1.0) * (mid - 2));
			int py = top + mid + (int) Math.round(Math.clamp((p.z() - z) / range, -1.0, 1.0) * (mid - 2));
			int colour = p.down() ? McTheme.RED : p.colour() < 0 ? McTheme.AQUA : 0xFF000000 | p.colour();
			graphics.fill(px - 2, py - 2, px + 2, py + 2, 0xFF000000);
			graphics.fill(px - 1, py - 1, px + 1, py + 1, colour);
			if (i < NAMED) {
				graphics.text(font, p.name(), px - 3 - font.width(p.name()), py - 4, colour, true);
			}
		}
	}

	public static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		if (!active() || UiHud.covered()) {
			return;
		}

		CasterWire.View v = view;
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int w = graphics.guiWidth(), h = graphics.guiHeight();
		// top: title, timer, Heat
		Component title = v.title().isEmpty() ? Component.translatableWithFallback("cyberblock.caster.title", "Caster view") : Component.literal(v.title());
		String clock = clock(v.timer());
		graphics.centeredText(font, clock.isEmpty() ? title : title.copy().append("  " + clock), w / 2, MARGIN, McTheme.YELLOW);
		int bx = w / 2 - HeatMeterHud.WIDTH / 2, by = MARGIN + LINE + 1;
		graphics.fill(bx - 1, by - 1, bx + HeatMeterHud.WIDTH + 1, by + HeatMeterHud.BAR + 1, 0xFF000000);
		graphics.fill(bx, by, bx + HeatMeterHud.WIDTH, by + HeatMeterHud.BAR, 0xFF3A3A3A);
		graphics.fill(bx, by, bx + Math.round(HeatMeterHud.WIDTH * v.heat() / 100.0F), by + HeatMeterHud.BAR, HeatMeterHud.colour(v.tier()));
		graphics.centeredText(font, Component.translatableWithFallback("cyberblock.heat.hud", "Heat %s · tier %s", v.heat(), v.tier()), w / 2,
			by + HeatMeterHud.BAR + 3, HeatMeterHud.colour(v.tier()));

		// left: teams
		int y = MARGIN;
		for (CasterWire.Team t : v.teams()) {
			graphics.text(font, t.name(), MARGIN, y, 0xFF000000 | t.colour(), true);
			y += LINE;
			for (CasterWire.Member m : t.players()) {
				Component name = m.down() ? Component.translatableWithFallback("cyberblock.caster.down", "%s (down)", m.name()) : Component.literal(m.name());
				graphics.text(font, name, MARGIN + 6, y, m.down() ? McTheme.RED : McTheme.WHITE, true);
				y += LINE;
			}

			y += 3;
		}

		// right: the kill feed, newest on top, under the status effect icons
		int fy = FEED_TOP;
		for (CasterWire.Feed f : v.feed()) {
			float alpha = Math.clamp(1.0F - (f.age() - (FEED_FADE_S - 5)) / 5.0F, 0.0F, 1.0F);
			if (alpha <= 0.05F) {
				continue;
			}

			Component line = line(f);
			graphics.text(font, line, w - MARGIN - font.width(line), fy, McTheme.fade(McTheme.WHITE, alpha), true);
			fy += LINE;
		}

		// bottom right: the pins on a map, north up, centred on the spectator
		if (minecraft.player != null) {
			map(graphics, font, v.pins(), w - MARGIN - MAP, h - MARGIN - MAP, minecraft.player.getX(), minecraft.player.getZ());
		}

		// bottom left: the board
		if (!v.board().isEmpty()) {
			int ry = h - MARGIN - LINE * (v.board().size() + 1);
			graphics.text(font, v.boardTitle(), MARGIN, ry, McTheme.GOLD, true);
			for (CasterWire.Row r : v.board()) {
				ry += LINE;
				graphics.text(font, r.name(), MARGIN, ry, McTheme.WHITE, true);
				String value = Integer.toString(r.value());
				graphics.text(font, value, MARGIN + 110 - font.width(value), ry, McTheme.YELLOW, true);
			}
		}
	}
}
