package dev.cyberblock.client.mode.match;

import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.client.ui.screen.AtlasView;
import dev.cyberblock.mode.match.MatchRules;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The match zone pick (megaplan 8-M1, "zones (Holo-Table area pick)"): the Atlas the Holo-Table's 2D mode uses, with the zone drawn
 * as a circle; a click puts its centre there, the radius buttons size it (16..512 blocks), "Use this zone" takes it back to the
 * Match Terminal's create form. Drag and wheel pan and zoom as on the Atlas.
 */
final class ZonePick implements AtlasScreen.Overlay {
	private static final double STEP = 1.25;
	private static final int RING = 0xFFFFAA00;
	private double x, z, r;
	private final Consumer<double[]> done;

	ZonePick(final double x, final double z, final double r, final Consumer<double[]> done) {
		this.x = x;
		this.z = z;
		this.r = r;
		this.done = done;
	}

	/** Opens the pick over {@code parent}, centred on the zone. */
	static void open(final MatchScreen parent, final double x, final double z, final double r, final Consumer<double[]> done) {
		ZonePick pick = new ZonePick(x, z, r, done);
		double zoom = Math.clamp(80.0 / Math.max(16.0, r), AtlasView.MIN_ZOOM, AtlasView.MAX_ZOOM);
		// a short title: the Atlas's tab buttons take the rest of the top row; the instruction is in the bottom line
		Minecraft.getInstance().gui.setScreen(new AtlasScreen(parent, Component.translatableWithFallback("cyberblock.match.zone.pick", "Match zone"),
			pick, x, z, zoom));
	}

	@Override
	public void init(final AtlasScreen screen, final Consumer<AbstractWidget> add) {
		int y = screen.height - 24, right = screen.width - 8;
		add.accept(Button.builder(Component.literal("r-"), b -> this.r = Math.max(MatchRules.MIN_RADIUS, Math.round(this.r / STEP)))
			.bounds(right - 124, y, 20, 20).build());
		add.accept(Button.builder(Component.literal("r+"), b -> this.r = Math.min(MatchRules.MAX_RADIUS, Math.round(this.r * STEP)))
			.bounds(right - 102, y, 20, 20).build());
		add.accept(Button.builder(Component.translatableWithFallback("cyberblock.match.zone.use", "Use this zone"), b -> {
			this.done.accept(new double[] {Math.round(this.x), Math.round(this.z), this.r});
			screen.onClose();
		}).bounds(right - 80, y, 80, 20).build());
	}

	@Override
	public void extract(final GuiGraphicsExtractor graphics, final AtlasView view, final double viewX, final double viewY, final int mouseX, final int mouseY) {
		int cx = (int) Math.round(view.screenX(this.x, viewX)), cy = (int) Math.round(view.screenY(this.z, viewY));
		double pr = this.r * view.zoom();
		int dots = (int) Math.clamp(pr * 0.8, 24, 360);
		for (int i = 0; i < dots; i++) {
			double a = Math.PI * 2.0 * i / dots;
			int px = (int) Math.round(cx + Math.cos(a) * pr), py = (int) Math.round(cy + Math.sin(a) * pr);
			graphics.fill(px, py, px + 2, py + 2, RING);
		}

		graphics.fill(cx - 4, cy, cx + 5, cy + 1, RING);
		graphics.fill(cx, cy - 4, cx + 1, cy + 5, RING);
	}

	@Override
	public void extractTop(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
		Minecraft minecraft = Minecraft.getInstance();
		Component line = Component.translatableWithFallback("cyberblock.match.zone.click", "Click the centre: %s, %s, radius %s blocks", Math.round(this.x),
			Math.round(this.z), Math.round(this.r));
		graphics.text(minecraft.font, line, 8, minecraft.getWindow().getGuiScaledHeight() - 36, 0xFFFFFFFF, true);
	}

	@Override
	public boolean click(final AtlasView view, final double viewX, final double viewY, final double sx, final double sy) {
		this.x = view.worldX(sx, viewX);
		this.z = view.worldZ(sy, viewY);
		return true;
	}
}
