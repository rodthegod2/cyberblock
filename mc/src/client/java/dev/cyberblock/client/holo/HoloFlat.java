package dev.cyberblock.client.holo;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.client.ui.screen.AtlasView;
import dev.cyberblock.holo.HoloWire;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table's 2D mode (megaplan 7-T1: "a 2D mode reuses AtlasScreen", also the fallback when the 3D table costs too much):
 * the Atlas with the table's pins (heads, [V], names), spawn points and fast-travel points drawn over it; a click selects one,
 * "Spawn here" goes, "3D" goes back to the table (absent when this PC fell back to 2D).
 */
final class HoloFlat implements AtlasScreen.Overlay {
	private final boolean back;
	private @Nullable Button action;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x, int y, String key, List<Component> tooltip) {
	}

	HoloFlat(final boolean back) {
		this.back = back;
	}

	@Override
	public void init(final AtlasScreen screen, final Consumer<AbstractWidget> add) {
		int y = screen.height - 24, x = screen.width - 8;
		this.action = Button.builder(HoloScreen.text("spawn", "Spawn here"), b -> {
			if (HoloScreen.go(HoloScreen.selectedEntry())) {
				Minecraft.getInstance().gui.setScreen(null);
			}
		}).bounds(x - 110, y, 110, 20).build();
		add.accept(this.action);
		if (this.back) {
			add.accept(Button.builder(HoloScreen.text("3d", "3D"), b -> screen.onClose()).bounds(x - 144, y, 30, 20).build());
		}
	}

	@Override
	public void extract(final GuiGraphicsExtractor graphics, final AtlasView view, final double viewX, final double viewY, final int mouseX, final int mouseY) {
		this.hits.clear();
		HoloWire.State s = HoloClient.state();
		for (HoloEntries.Entry e : HoloEntries.of(s, HoloFastTravel.list())) {
			if (e.group().equals("friend")) {
				continue;
			}

			int x = (int) Math.round(view.screenX(e.x(), viewX)), y = (int) Math.round(view.screenY(e.z(), viewY));
			if (e.key().equals(HoloScreen.selected)) {
				graphics.outline(x - 7, y - 7, 14, 14, McTheme.YELLOW);
			}

			HoloScreen.marker(graphics, e, x, y);
			this.hits.add(new Hit(x, y, e.key(), HoloScreen.entryTooltip(e)));
		}

		if (s != null) {
			for (HoloWire.Pin p : s.pins()) {
				int x = (int) Math.round(view.screenX(p.x(), viewX)), y = (int) Math.round(view.screenY(p.z(), viewY));
				String key = "friend:" + p.id();
				if (key.equals(HoloScreen.selected)) {
					graphics.outline(x - 7, y - 7, 14, 14, McTheme.YELLOW);
				}

				graphics.fill(x - 5, y - 5, x + 5, y + 5, p.you() ? McTheme.GOLD : p.colour() >= 0 ? 0xFF000000 | p.colour() : 0xFFE0E0E0);
				PlayerFaceExtractor.extractRenderState(graphics, HoloScreen.skin(p), x - 4, y - 4, 8);
				Component name = HoloScreen.pinName(p);
				graphics.text(Minecraft.getInstance().font, name, x - Minecraft.getInstance().font.width(name) / 2, y + 7, McTheme.WHITE, true);
				this.hits.add(new Hit(x, y, p.you() ? "" : key, HoloScreen.pinTooltip(p)));
			}
		}

		if (this.action != null) {
			HoloEntries.Entry e = HoloScreen.selectedEntry();
			this.action.visible = e != null;
			this.action.active = e != null && (e.ok() || e.point() != null);
			if (e != null) {
				this.action.setMessage(e.point() != null ? HoloScreen.text("fast_travel", "Fast travel") : HoloScreen.text("spawn", "Spawn here"));
			}
		}
	}

	@Override
	public void extractTop(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
		for (Hit h : this.hits) {
			if (Math.abs(mouseX - h.x()) <= 6 && Math.abs(mouseY - h.y()) <= 6) {
				graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, h.tooltip(), mouseX, mouseY);
				return;
			}
		}
	}

	@Override
	public boolean click(final AtlasView view, final double viewX, final double viewY, final double x, final double y) {
		for (int i = this.hits.size() - 1; i >= 0; i--) {
			Hit h = this.hits.get(i);
			if (!h.key().isEmpty() && Math.abs(x - h.x()) <= 6 && Math.abs(y - h.y()) <= 6) {
				HoloScreen.selected = h.key();
				return true;
			}
		}

		return false;
	}
}
