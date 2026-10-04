package dev.cyberblock.client.mode.overlord;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.client.ui.screen.AtlasView;
import dev.cyberblock.mode.overlord.OverlordWire;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Overlord Console (megaplan 8-O1) drawn on the Atlas: the top-down replica of the match area with its outline, the match's
 * players (green: opted in, grey: not), the spawns and events still in their warning (a red ring and a countdown) and the picked
 * spot; on the right the palette panel: Threat Points, intensity and the cap, the Spawns and Events tabs (Night City entries are
 * marked NC: a Minecraft squad stands in where they cannot be spawned), Send, and the event timeline. Click the map to pick the
 * spot, pick an entry, Send. The server decides everything (fairness, cost, ground); a refusal comes back on the action bar and
 * under the map.
 */
final class OverlordConsole implements AtlasScreen.Overlay {
	static final int PANEL_W = 156;
	private static final int MARGIN = 8, TOP = 26, BOTTOM = 28, ROW_H = 18;
	private static final int OPTED = 0xFF40E060, NOT_OPTED = 0xFFA0A0A0, WARNING = 0xFFFF2040, AREA = 0xC0FF4060, TARGET = 0xFFFFE040;

	private boolean events;
	private int page;
	private @Nullable String selected;
	private double tx = Double.NaN, tz = Double.NaN;
	private final List<Button> rows = new ArrayList<>();
	private @Nullable Button spawnsTab, eventsTab, send, prev, next;
	private int x0, y0, x1, y1, rowsY, rowCount, timelineY;

	@Override
	public void init(final AtlasScreen screen, final Consumer<AbstractWidget> add) {
		this.x1 = screen.width - MARGIN;
		this.x0 = this.x1 - PANEL_W;
		this.y0 = TOP;
		this.y1 = screen.height - BOTTOM;
		int half = (PANEL_W - 12) / 2;
		this.spawnsTab = Button.builder(text("spawns", "Spawns"), b -> this.tab(false)).bounds(this.x0 + 4, this.y0 + 40, half, 16).build();
		this.eventsTab = Button.builder(text("events", "Events"), b -> this.tab(true)).bounds(this.x0 + 8 + half, this.y0 + 40, half, 16).build();
		add.accept(this.spawnsTab);
		add.accept(this.eventsTab);
		this.rowsY = this.y0 + 60;
		int room = this.y1 - this.rowsY - 22 - 18 - 40;
		this.rowCount = Math.max(3, Math.min(10, room / ROW_H));
		this.rows.clear();
		for (int i = 0; i < this.rowCount; i++) {
			int row = i;
			Button b = Button.builder(Component.empty(), btn -> this.pick(row)).bounds(this.x0 + 4, this.rowsY + i * ROW_H, PANEL_W - 8, 16).build();
			this.rows.add(b);
			add.accept(b);
		}

		int pagerY = this.rowsY + this.rowCount * ROW_H;
		this.prev = Button.builder(Component.literal("<"), b -> this.page = Math.max(0, this.page - 1)).bounds(this.x0 + 4, pagerY, 20, 14).build();
		this.next = Button.builder(Component.literal(">"), b -> this.page++).bounds(this.x1 - 24, pagerY, 20, 14).build();
		add.accept(this.prev);
		add.accept(this.next);
		this.timelineY = pagerY + 18;
		this.send = Button.builder(Component.empty(), b -> this.send()).bounds(this.x0 + 4, this.y1 - 22, PANEL_W - 8, 18).build();
		add.accept(this.send);
	}

	static Component text(final String key, final String english, final Object... args) {
		return Component.translatableWithFallback("cyberblock.overlord.console." + key, english, args);
	}

	private void tab(final boolean events) {
		if (this.events != events) {
			this.events = events;
			this.page = 0;
			this.selected = null;
		}
	}

	private List<OverlordWire.Option> options() {
		OverlordWire.State s = OverlordClient.state();
		return s == null ? List.of() : this.events ? s.events() : s.spawns();
	}

	private void pick(final int row) {
		int i = this.page * this.rowCount + row;
		List<OverlordWire.Option> list = this.options();
		if (i < list.size()) {
			this.selected = list.get(i).id();
		}
	}

	private OverlordWire.@Nullable Option selectedOption() {
		for (OverlordWire.Option o : this.options()) {
			if (o.id().equals(this.selected)) {
				return o;
			}
		}

		return null;
	}

	private void send() {
		OverlordWire.Option o = this.selectedOption();
		if (o != null && Double.isFinite(this.tx)) {
			OverlordClient.ask(this.events, o.id(), this.tx, this.tz);
		}
	}

	// ---- on the map ----

	@Override
	public void extract(final GuiGraphicsExtractor graphics, final AtlasView view, final double viewX, final double viewY, final int mouseX, final int mouseY) {
		Font font = Minecraft.getInstance().font;
		OverlordWire.State s = OverlordClient.state();
		if (s != null) {
			double[] a = s.area();
			if (a != null) {
				int ax0 = (int) Math.round(view.screenX(a[0], viewX)), az0 = (int) Math.round(view.screenY(a[1], viewY));
				int ax1 = (int) Math.round(view.screenX(a[2], viewX)), az1 = (int) Math.round(view.screenY(a[3], viewY));
				graphics.outline(ax0, az0, Math.max(1, ax1 - ax0), Math.max(1, az1 - az0), AREA);
			}

			boolean spawning = !this.events;
			for (OverlordWire.Player p : s.players()) {
				int px = (int) Math.round(view.screenX(p.x(), viewX)), py = (int) Math.round(view.screenY(p.z(), viewY));
				if (spawning) {
					ring(graphics, px, py, s.minDist() * view.zoom(), 0x60FFFFFF);
				}

				graphics.fill(px - 3, py - 3, px + 3, py + 3, p.opted() ? OPTED : NOT_OPTED);
				graphics.outline(px - 4, py - 4, 8, 8, 0xFF000000);
				String name = (p.linked() ? "[V] " : "") + p.name();
				graphics.text(font, name, px - font.width(name) / 2, py + 5, p.opted() ? OPTED : NOT_OPTED, true);
			}

			for (OverlordWire.Mark m : s.pending()) {
				int px = (int) Math.round(view.screenX(m.x(), viewX)), py = (int) Math.round(view.screenY(m.z(), viewY));
				ring(graphics, px, py, Math.max(5.0, m.r() * view.zoom()), WARNING);
				graphics.fill(px - 2, py - 2, px + 2, py + 2, WARNING);
				String label = m.name() + " " + (m.left() + 19) / 20 + "s";
				graphics.text(font, label, px - font.width(label) / 2, py - 14, WARNING, true);
			}
		}

		if (Double.isFinite(this.tx)) {
			int px = (int) Math.round(view.screenX(this.tx, viewX)), py = (int) Math.round(view.screenY(this.tz, viewY));
			graphics.fill(px - 6, py, px + 7, py + 1, TARGET);
			graphics.fill(px, py - 6, px + 1, py + 7, TARGET);
		}

		this.panel(graphics, font, s);
	}

	/** A one-pixel circle of {@code r} GUI px (the minimum distance, a warning). */
	private static void ring(final GuiGraphicsExtractor graphics, final int cx, final int cy, final double r, final int colour) {
		int n = (int) Math.max(12, Math.min(64, r));
		for (int i = 0; i < n; i++) {
			double a = i * Math.PI * 2.0 / n;
			int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
			graphics.fill(x, y, x + 1, y + 1, colour);
		}
	}

	/** The palette panel's background and text (its buttons are widgets, drawn after this). */
	private void panel(final GuiGraphicsExtractor graphics, final Font font, final OverlordWire.@Nullable State s) {
		McTheme.panel(graphics, this.x0, this.y0, PANEL_W, this.y1 - this.y0);
		int x = this.x0 + 6;
		if (s == null) {
			graphics.text(font, "...", x, this.y0 + 6, McTheme.PANEL_TEXT, false);
			this.layoutRows(List.of(), null);
			return;
		}

		graphics.text(font, text("points", "Threat Points %s / %s", s.tp(), s.tpMax()), x, this.y0 + 5, McTheme.PANEL_TEXT, false);
		McTheme.bossBar(graphics, x, this.y0 + 16, PANEL_W - 12, s.tpMax() <= 0 ? 0.0F : (float) s.tp() / s.tpMax());
		graphics.text(font, text("status", "%s · %s/%s alive · +%s/s", intensity(s.intensity()), s.live(), s.cap(), fmt(s.regen())), x, this.y0 + 25,
			McTheme.PANEL_TEXT, false);
		this.layoutRows(this.options(), s);
		graphics.text(font, text("timeline", "Timeline"), x, this.timelineY, McTheme.PANEL_TEXT, false);
		int y = this.timelineY + 10;
		for (OverlordWire.Line line : s.timeline()) {
			if (y > this.y1 - 34) {
				break;
			}

			String t = ago(line.ago()) + " " + line.text();
			graphics.text(font, font.plainSubstrByWidth(t, PANEL_W - 12), x, y, 0xFF303030, false);
			y += 10;
		}
	}

	/** Each frame: the row labels, the tabs, the pager and Send from the newest state. */
	private void layoutRows(final List<OverlordWire.Option> list, final OverlordWire.@Nullable State s) {
		int pages = Math.max(1, (list.size() + this.rowCount - 1) / this.rowCount);
		this.page = Math.min(this.page, pages - 1);
		for (int i = 0; i < this.rows.size(); i++) {
			int idx = this.page * this.rowCount + i;
			Button b = this.rows.get(i);
			b.visible = idx < list.size();
			if (b.visible) {
				OverlordWire.Option o = list.get(idx);
				String label = (o.nc() ? "NC " : "") + o.name() + (o.count() > 0 && !this.events ? " x" + o.count() : "") + "  " + o.cost();
				b.setMessage(Component.literal(label).withStyle(o.ok() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
			}
		}

		if (this.prev != null && this.next != null) {
			this.prev.visible = this.next.visible = pages > 1;
			this.prev.active = this.page > 0;
			this.next.active = this.page < pages - 1;
		}

		if (this.spawnsTab != null && this.eventsTab != null) {
			this.spawnsTab.active = this.events;
			this.eventsTab.active = !this.events;
		}

		if (this.send != null) {
			OverlordWire.Option o = this.selectedOption();
			this.send.active = s != null && o != null && o.ok() && Double.isFinite(this.tx);
			this.send.setMessage(o == null ? text("pick", "Pick an entry")
				: !Double.isFinite(this.tx) ? text("spot", "Click the map for a spot")
				: o.cooldown() > 0 ? text("cooldown", "Ready in %s s", o.cooldown())
				: text("send", "Send %s (%s)", o.name(), o.cost()));
		}
	}

	@Override
	public void extractTop(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
		Font font = Minecraft.getInstance().font;
		OverlordWire.State s = OverlordClient.state();
		List<OverlordWire.Option> list = this.options();
		for (int i = 0; i < this.rows.size(); i++) {
			Button b = this.rows.get(i);
			int idx = this.page * this.rowCount + i;
			if (!b.visible || idx >= list.size()) {
				continue;
			}

			OverlordWire.Option o = list.get(idx);
			if (o.id().equals(this.selected)) {
				graphics.outline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, McTheme.YELLOW);
			}

			if (b.isHovered()) {
				List<Component> lines = new ArrayList<>();
				lines.add(Component.literal(o.name()).withStyle(ChatFormatting.YELLOW));
				if (!o.desc().isEmpty()) {
					lines.add(Component.literal(o.desc()).withStyle(ChatFormatting.GRAY));
				}

				lines.add(text("cost", "Cost: %s Threat Points", o.cost()));
				if (o.nc()) {
					lines.add(text("nc", "Night City: spawned in a nearby host's game when its spawns toggle allows; a Minecraft squad otherwise")
						.copy().withStyle(ChatFormatting.DARK_AQUA));
				}

				if (o.cooldown() > 0) {
					lines.add(text("cooldown", "Ready in %s s", o.cooldown()).copy().withStyle(ChatFormatting.RED));
				}

				graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
			}
		}

		if (s == null) {
			return;
		}

		int y = graphics.guiHeight() - BOTTOM + 10, x = 90;
		if (!s.note().isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(s.note(), this.x0 - x - 4), x, y, McTheme.RED, true);
		} else if (s.director()) {
			graphics.text(font, text("director", "Auto-Director is running this match"), x, y, McTheme.GRAY, true);
		} else {
			graphics.text(font, text("hint", "Pick an entry, click a spot, Send: it lands 3 s later"), x, y, McTheme.GRAY, true);
		}
	}

	@Override
	public boolean click(final AtlasView view, final double viewX, final double viewY, final double x, final double y) {
		if (x >= this.x0) {
			return true; // the panel: never a map click
		}

		this.tx = view.worldX(x, viewX);
		this.tz = view.worldZ(y, viewY);
		return true;
	}

	private static Component intensity(final String key) {
		return text("intensity." + key, key.isEmpty() ? "" : Character.toUpperCase(key.charAt(0)) + key.substring(1));
	}

	private static String fmt(final double v) {
		return v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.1f", v);
	}

	private static String ago(final int seconds) {
		return seconds < 60 ? seconds + "s" : seconds / 60 + "m";
	}
}
