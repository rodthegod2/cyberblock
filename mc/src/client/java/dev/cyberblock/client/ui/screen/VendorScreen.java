package dev.cyberblock.client.ui.screen;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import dev.cyberblock.client.ui.VendorModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A host's Night City vendor or ripperdoc as a Minecraft screen (megaplan 7-V1): opened by the {@code reply kind:"vendor"} that
 * Vendors.reds sends when the host talks to a vendor with Cyberblock On, in a container panel: the vendor's stock by category on
 * the left (buy), V's items the vendor takes on the right (sell). A click trades one, a shift-click {@link VendorModel#BULK}; the
 * host pays with its real Cyberpunk money (shown from {@code stats.money}), and the scripts answer with a refreshed reply.
 * "Night City vendor..." opens Cyberpunk's own vendor screen once (installing cyberware, buyback, anything not shown here).
 */
public final class VendorScreen extends Screen {
	private static final int MAX_W = 420;
	private static final int MAX_H = 270;
	private static final int LINE = 11;
	private static final int HEADER = 30;
	private static final int FOOTER = 40;
	/** A trade waits this long for the refreshed reply before the lists take clicks again. */
	private static final long PENDING_NANOS = 2_000_000_000L;

	private VendorModel.Vendor vendor;
	private final List<Row> buyRows = new ArrayList<>();
	private final List<Row> sellRows = new ArrayList<>();
	private int buyScroll;
	private int sellScroll;
	private long pendingSince;
	private boolean pending;

	/** One list row: a category header ({@code item} null) or an item. */
	private record Row(String header, VendorModel.@Nullable Item item) {
	}

	private VendorScreen(final VendorModel.Vendor vendor) {
		super(title(vendor));
		this.vendor = vendor;
		this.rebuild();
	}

	/** Client thread: shows the vendor, or refreshes the open vendor screen. A Minecraft screen the player has open is left alone. */
	public static void show(final VendorModel.Vendor vendor) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		Screen current = minecraft.gui.screen();
		if (current instanceof VendorScreen open) {
			open.update(vendor);
			return;
		}

		if (current != null) {
			// the scripts see no Minecraft screen open for it and keep Night City's own vendor screen
			Cyberblock.LOG.info("vendor: {} not opened over {}", vendor.name(), current.getClass().getSimpleName());
			return;
		}

		Cyberblock.LOG.info("vendor: {} ({}) opened: {} to buy, {} to sell", vendor.name(), vendor.ripperdoc() ? "ripperdoc" : "vendor", vendor.buy().size(),
			vendor.sell().size());
		minecraft.gui.setScreen(new VendorScreen(vendor));
	}

	/** Client thread: Cyberblock went off or the host session changed; the vendor screen no longer stands for anything. */
	public static void closeIfOpen() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.screen() instanceof VendorScreen) {
			minecraft.gui.setScreen(null);
		}
	}

	private static Component title(final VendorModel.Vendor vendor) {
		if (!vendor.name().isEmpty()) {
			return Component.literal(vendor.name());
		}

		return vendor.ripperdoc() ? NightCityPauseScreen.ui("vendor.ripperdoc", "Ripperdoc") : NightCityPauseScreen.ui("vendor.title", "Vendor");
	}

	private void update(final VendorModel.Vendor next) {
		this.vendor = next;
		this.pending = false;
		this.rebuild();
	}

	private void rebuild() {
		fill(this.buyRows, this.vendor.buy());
		fill(this.sellRows, this.vendor.sell());
	}

	private static void fill(final List<Row> rows, final List<VendorModel.Item> items) {
		rows.clear();
		String category = null;
		for (VendorModel.Item item : items) {
			if (!item.category().equals(category)) {
				category = item.category();
				rows.add(new Row(category, null));
			}

			rows.add(new Row("", item));
		}
	}

	private int panelW() {
		return Math.min(this.width - 16, MAX_W);
	}

	private int panelH() {
		return Math.min(this.height - 16, MAX_H);
	}

	private int left() {
		return (this.width - this.panelW()) / 2;
	}

	private int top() {
		return (this.height - this.panelH()) / 2;
	}

	private int columnW() {
		return (this.panelW() - 24) / 2;
	}

	private int columnX(final boolean buy) {
		return this.left() + 8 + (buy ? 0 : this.columnW() + 8);
	}

	private int listTop() {
		return this.top() + HEADER;
	}

	private int listH() {
		return this.panelH() - HEADER - FOOTER;
	}

	private int visibleRows() {
		return Math.max(1, (this.listH() - 4) / LINE);
	}

	@Override
	protected void init() {
		int y = this.top() + this.panelH() - 26;
		int w = (this.panelW() - 24) / 2;
		this.addRenderableWidget(Button.builder(NightCityPauseScreen.ui("vendor.night_city", "Night City vendor..."), b -> {
			// the escape hatch: Cyberpunk's own vendor screen, once (Vendors.reds lets the next one through)
			if (UiLink.act("cpmenu", "vendor", 0L, Math.max(0, this.vendor.session()))) {
				this.minecraft.gui.setScreen(null);
			}
		}).bounds(this.left() + 8, y, w, 20).build());
		this.addRenderableWidget(Button.builder(NightCityPauseScreen.ui("vendor.done", "Done"), b -> this.onClose()).bounds(this.left() + 16 + w, y, w, 20).build());
	}

	private boolean busy() {
		if (this.pending && System.nanoTime() - this.pendingSince > PENDING_NANOS) {
			this.pending = false;
		}

		return this.pending || !UiLink.linked();
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		int left = this.left(), top = this.top();
		McTheme.panel(graphics, left, top, this.panelW(), this.panelH());
		graphics.text(this.font, this.font.substrByWidth(title(this.vendor), this.panelW() / 2).getString(), left + 8, top + 6, McTheme.PANEL_TEXT, false);
		UiModel.Stats stats = UiModel.stats;
		Component money = NightCityPauseScreen.ui("vendor.money", "Your eddies: %s", stats != null ? VendorModel.eddies(stats.money()) : "?");
		graphics.text(this.font, money, left + this.panelW() - 8 - this.font.width(money), top + 6, McTheme.PANEL_TEXT, false);
		graphics.text(this.font, NightCityPauseScreen.ui("vendor.buy", "Buy"), this.columnX(true), top + 19, McTheme.PANEL_TEXT, false);
		graphics.text(this.font, NightCityPauseScreen.ui("vendor.sell", "Sell"), this.columnX(false), top + 19, McTheme.PANEL_TEXT, false);
		VendorModel.Item hovered = this.drawColumn(graphics, true, mouseX, mouseY);
		VendorModel.Item hoveredSell = this.drawColumn(graphics, false, mouseX, mouseY);
		Component status = !UiLink.linked() ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected")
			: this.pending ? NightCityPauseScreen.ui("vendor.pending", "Trading...")
			: NightCityPauseScreen.ui("vendor.hint", "Click: trade 1 · Shift-click: trade %s", VendorModel.BULK);
		graphics.centeredText(this.font, status, left + this.panelW() / 2, top + this.panelH() - 37, UiLink.linked() ? McTheme.PANEL_TEXT : McTheme.RED);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (hovered != null) {
			this.tooltip(graphics, hovered, true, mouseX, mouseY);
		} else if (hoveredSell != null) {
			this.tooltip(graphics, hoveredSell, false, mouseX, mouseY);
		}
	}

	/** One list in its slot; returns the hovered item (null none). */
	private VendorModel.@Nullable Item drawColumn(final GuiGraphicsExtractor graphics, final boolean buy, final int mouseX, final int mouseY) {
		int x = this.columnX(buy), y = this.listTop(), w = this.columnW(), h = this.listH();
		McTheme.slot(graphics, x, y, w, h);
		List<Row> rows = buy ? this.buyRows : this.sellRows;
		if (rows.isEmpty()) {
			graphics.text(this.font, buy ? NightCityPauseScreen.ui("vendor.no_stock", "Nothing for sale") : NightCityPauseScreen.ui("vendor.nothing_to_sell", "Nothing to sell"),
				x + 4, y + 4, McTheme.GRAY, false);
			return null;
		}

		int scroll = Math.clamp(buy ? this.buyScroll : this.sellScroll, 0, Math.max(0, rows.size() - this.visibleRows()));
		if (buy) {
			this.buyScroll = scroll;
		} else {
			this.sellScroll = scroll;
		}

		boolean busy = this.busy();
		UiModel.Stats stats = UiModel.stats;
		VendorModel.Item hovered = null;
		int rowY = y + 3;
		for (int i = scroll; i < rows.size() && i < scroll + this.visibleRows(); i++) {
			Row row = rows.get(i);
			VendorModel.Item item = row.item();
			if (item == null) {
				graphics.text(this.font, this.font.substrByWidth(Component.literal(row.header()), w - 8).getString(), x + 3, rowY, McTheme.YELLOW, false);
			} else {
				boolean over = mouseX >= x && mouseX < x + w && mouseY >= rowY - 1 && mouseY < rowY + LINE - 1;
				if (over) {
					hovered = item;
					graphics.fill(x + 1, rowY - 1, x + w - 1, rowY + LINE - 1, 0x40FFFFFF);
				}

				String price = VendorModel.eddies(item.price());
				int priceW = this.font.width(price);
				boolean can = !buy || VendorModel.affordable(item, stats);
				int colour = busy ? McTheme.GRAY : can ? McTheme.WHITE : McTheme.RED;
				graphics.text(this.font, this.font.substrByWidth(Component.literal(item.name()), w - priceW - 14).getString(), x + 7, rowY, colour, false);
				graphics.text(this.font, price, x + w - 4 - priceW, rowY, busy ? McTheme.GRAY : can ? McTheme.GOLD : McTheme.RED, false);
			}

			rowY += LINE;
		}

		return hovered;
	}

	private void tooltip(final GuiGraphicsExtractor graphics, final VendorModel.Item item, final boolean buy, final int mouseX, final int mouseY) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(item.name()).withStyle(ChatFormatting.YELLOW));
		lines.add(Component.literal(item.category()).withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal(VendorModel.eddies(item.price())).withStyle(ChatFormatting.GOLD));
		if (buy && !VendorModel.affordable(item, UiModel.stats)) {
			lines.add(NightCityPauseScreen.ui("vendor.too_expensive", "Not enough eddies").withStyle(ChatFormatting.RED));
		} else {
			lines.add(buy ? NightCityPauseScreen.ui("vendor.click_buy", "Click: buy 1 · Shift-click: buy %s", VendorModel.BULK).withStyle(ChatFormatting.DARK_GRAY)
				: NightCityPauseScreen.ui("vendor.click_sell", "Click: sell 1 · Shift-click: sell %s", VendorModel.BULK).withStyle(ChatFormatting.DARK_GRAY));
		}

		graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		for (boolean buy : new boolean[] {true, false}) {
			VendorModel.Item item = this.itemAt(buy, event.x(), event.y());
			if (item != null) {
				this.trade(buy, item, event.hasShiftDown());
				return true;
			}
		}

		return false;
	}

	private VendorModel.@Nullable Item itemAt(final boolean buy, final double mouseX, final double mouseY) {
		int x = this.columnX(buy);
		if (mouseX < x || mouseX >= x + this.columnW() || mouseY < this.listTop() + 2) {
			return null;
		}

		List<Row> rows = buy ? this.buyRows : this.sellRows;
		int row = (int) Math.floor((mouseY - this.listTop() - 2) / LINE);
		int index = (buy ? this.buyScroll : this.sellScroll) + row;
		return row < this.visibleRows() && index >= 0 && index < rows.size() ? rows.get(index).item() : null;
	}

	/** {@code uiact buy|sell}; the scripts check the stack, the money and the distance again and answer with a fresh reply. */
	private void trade(final boolean buy, final VendorModel.Item item, final boolean shift) {
		if (this.busy() || (buy && !VendorModel.affordable(item, UiModel.stats))) {
			return;
		}

		if (UiLink.act(buy ? "buy" : "sell", Integer.toString(VendorModel.quantity(shift)), item.hash(), item.index())) {
			this.pending = true;
			this.pendingSince = System.nanoTime();
		}
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		int step = (int) Math.signum(scrollY);
		if (x < this.columnX(false)) {
			this.buyScroll -= step;
		} else {
			this.sellScroll -= step;
		}

		return true;
	}

	@Override
	public void onClose() {
		Cyberblock.LOG.info("vendor: {} closed", this.vendor.name());
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
