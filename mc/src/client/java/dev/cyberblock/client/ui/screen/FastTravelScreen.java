package dev.cyberblock.client.ui.screen;

import dev.cyberblock.client.ui.FastTravelPoints;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The host's Night City fast travel, offered by the Holo-Table (megaplan 7-V1; the Atlas stays map-only). Lists the fast-travel
 * points V has found, by district (FastTravelPoints: {@code uiact atlasreq arg:"fasttravel"}); clicking one sends {@code uiact
 * fasttravel} and closes the screens: Cyberpunk shows its loading screen and V arrives there. While the host cannot fast travel
 * (a job locks it, combat, a vehicle, a scene) the points are listed greyed and the scripts say why in a toast.
 * The Holo-Table (package 7T) opens it with {@code new FastTravelScreen(parent)}.
 */
public final class FastTravelScreen extends Screen {
	private static final int MAX_W = 260;
	private static final int LINE = 11;
	private static final int TOP = 34;
	private static final int BOTTOM = 34;

	private final @Nullable Screen parent;
	private List<UiModel.ReplyPin> points = List.of();
	private boolean answered;
	private int requestId = -1;
	private int scroll;
	/** The rows of the last frame: a district header (pin null) or a point. */
	private final List<UiModel.@Nullable ReplyPin> rows = new ArrayList<>();

	public FastTravelScreen(final @Nullable Screen parent) {
		super(NightCityPauseScreen.ui("fast_travel.title", "Fast Travel"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (this.requestId < 0 && !this.answered) {
			this.requestId = UiLink.request("atlas", "fasttravel", r -> {
				this.points = FastTravelPoints.of(r);
				this.answered = true;
			});
		}

		this.addRenderableWidget(Button.builder(NightCityPauseScreen.ui("fast_travel.back", "Back"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - BOTTOM + 8, 100, 20).build());
	}

	private int panelW() {
		return Math.min(this.width - 16, MAX_W);
	}

	private int left() {
		return (this.width - this.panelW()) / 2;
	}

	private int visibleRows() {
		return Math.max(1, (this.height - TOP - BOTTOM - 4) / LINE);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		int left = this.left(), w = this.panelW();
		graphics.centeredText(this.font, this.title, this.width / 2, 8, McTheme.WHITE);
		boolean available = FastTravelPoints.available(this.points);
		Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected")
			: !this.answered ? Component.literal("...")
			: this.points.isEmpty() ? NightCityPauseScreen.ui("fast_travel.none", "V has not found a fast-travel point yet")
			: available ? NightCityPauseScreen.ui("fast_travel.pick", "Pick a destination")
			: NightCityPauseScreen.ui("fast_travel.off", "Fast travel is not possible right now");
		graphics.centeredText(this.font, status, this.width / 2, 20, this.requestId < 0 || (this.answered && !available) ? McTheme.RED : McTheme.GRAY);
		McTheme.slot(graphics, left, TOP, w, this.height - TOP - BOTTOM);
		this.rows.clear();
		String district = null;
		for (UiModel.ReplyPin point : this.points) {
			if (!point.district().equals(district)) {
				district = point.district();
				this.rows.add(null);
			}

			this.rows.add(point);
		}

		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, this.rows.size() - this.visibleRows()));
		int y = TOP + 3;
		for (int i = this.scroll; i < this.rows.size() && i < this.scroll + this.visibleRows(); i++) {
			UiModel.ReplyPin point = this.rows.get(i);
			if (point == null) {
				UiModel.ReplyPin next = i + 1 < this.rows.size() ? this.rows.get(i + 1) : null;
				String name = next == null || next.district().isEmpty() ? NightCityPauseScreen.ui("fast_travel.elsewhere", "Night City").getString() : next.district();
				graphics.text(this.font, this.font.substrByWidth(Component.literal(name), w - 8).getString(), left + 3, y, McTheme.YELLOW, false);
			} else {
				boolean over = point.index() >= 0 && mouseX >= left && mouseX < left + w && mouseY >= y - 1 && mouseY < y + LINE - 1;
				if (over) {
					graphics.fill(left + 1, y - 1, left + w - 1, y + LINE - 1, 0x40FFFFFF);
				}

				graphics.text(this.font, this.font.substrByWidth(Component.literal(point.name()), w - 12).getString(), left + 7, y,
					point.index() >= 0 ? McTheme.WHITE : McTheme.GRAY, false);
			}

			y += LINE;
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		int left = this.left();
		int row = (int) Math.floor((event.y() - TOP - 2) / LINE);
		int index = this.scroll + row;
		if (event.x() < left || event.x() >= left + this.panelW() || row < 0 || row >= this.visibleRows() || index >= this.rows.size()) {
			return false;
		}

		UiModel.ReplyPin point = this.rows.get(index);
		if (point != null && point.index() >= 0 && UiLink.act("fasttravel", point.name(), 0L, point.index())) {
			this.minecraft.gui.setScreen(null);
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		this.scroll -= (int) Math.signum(scrollY);
		return true;
	}

	@Override
	public void removed() {
		UiLink.forget(this.requestId);
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
