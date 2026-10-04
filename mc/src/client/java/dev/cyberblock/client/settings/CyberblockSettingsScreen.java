package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.OutlineRules;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberblock settings screen (plan Q-B5, section 1.13), vanilla widgets only. Its first row is the server setting "Creative
 * can erase the replica permanently": the toggle sends {@code /cyberblock outline erase on|off} and the server decides. Players who
 * may not change it see it greyed with a tooltip. Opens from the "Cyberblock..." button of the pause screens and with
 * {@code /cyberblock settings}. Later packages add their rows with {@link #addRow}.
 */
public final class CyberblockSettingsScreen extends Screen {
	/** A row another package adds (the view rows of QV, later phases' rows): a widget at x, y of the given width, 20 high. */
	@FunctionalInterface
	public interface Row {
		AbstractWidget create(int x, int y, int width);
	}

	private static final int WIDTH = 310;
	private static final int ROW = 24;
	private static final List<Row> ROWS = new CopyOnWriteArrayList<>();
	private final @Nullable Screen parent;

	private CyberblockSettingsScreen(final @Nullable Screen parent) {
		super(Component.translatableWithFallback("cyberblock.settings.title", "Cyberblock settings"));
		this.parent = parent;
	}

	/** Opens the screen over parent (null: back to the game when it closes). QU's Night City pause screen calls this. */
	public static void open(final @Nullable Screen parent) {
		Minecraft.getInstance().gui.setScreen(new CyberblockSettingsScreen(parent));
	}

	/** Adds a row below the built-in ones, for every screen opened from now on (client thread, at start-up). */
	public static void addRow(final Row row) {
		ROWS.add(row);
	}

	/**
	 * Whether this player may change the server settings, as far as the client can tell: the owner of the integrated server (a
	 * singleplayer world, also opened to LAN) may. Elsewhere the row is greyed: the client cannot read a remote server's value
	 * before Phase 1 sends it. The server checks again whatever the screen says (OutlineRules.mayChange).
	 */
	private boolean ownsTheServer() {
		IntegratedServer server = this.minecraft.getSingleplayerServer();
		return server != null && this.minecraft.player != null && server.isSingleplayerOwner(this.minecraft.player.nameAndId());
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		int y = this.height / 6;
		boolean owner = this.ownsTheServer();
		CycleButton<Boolean> erase = CycleButton.onOffBuilder(owner && OutlineRules.creativeErases()).create(left, y, WIDTH, 20,
			Component.translatableWithFallback("cyberblock.outline.erase", "Creative can erase the replica permanently"), (button, on) -> this.setErase(on));
		if (!owner) {
			erase.active = false;
			erase.setTooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.settings.locked", "Only the host or an operator can change this")));
		}

		this.addRenderableWidget(erase);
		for (Row row : ROWS) {
			y += ROW;
			this.addRenderableWidget(row.create(left, y, WIDTH));
		}

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 100, Math.max(y + ROW + 8, this.height - 28), 200, 20).build());
	}

	private void setErase(final boolean on) {
		if (this.minecraft.player != null) {
			Cyberblock.LOG.info("settings: creative erase {}", on ? "on" : "off");
			this.minecraft.player.connection.sendCommand("cyberblock outline erase " + (on ? "on" : "off"));
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
