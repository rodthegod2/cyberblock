package dev.cyberblock.client.hack.screen;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.actions.RemoteHack;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The small interaction map panels (Vehicle Panel, Traffic Tool, Comms Panel) in the Minecraft container look (McTheme): a panel
 * with a title, buttons laid out by the subclass, and the answer to the last request sent from this panel at the bottom. The game
 * keeps running behind it. Client thread.
 */
abstract class ToolScreen extends Screen {
	static final int PAD = 8;
	static final int ROW = 22;
	private final int panelW;
	private final int panelH;
	/** When this panel sent its last request (answers before it belong to another panel). */
	private long sentAt = Long.MAX_VALUE;
	private Component status = Component.empty();
	private int statusColour = McTheme.PANEL_TEXT;
	private int ticks;

	ToolScreen(final Component title, final int panelW, final int panelH) {
		super(title);
		this.panelW = panelW;
		this.panelH = panelH;
	}

	int left() {
		return (this.width - this.panelW) / 2;
	}

	int top() {
		return (this.height - this.panelH) / 2;
	}

	int panelW() {
		return this.panelW;
	}

	/** A full-width button on row `row` (0 is the first under the title). */
	Button row(final int row, final Component label, final Button.OnPress press) {
		return this.addRenderableWidget(Button.builder(label, press).bounds(this.left() + PAD, this.top() + 20 + row * ROW, this.panelW - 2 * PAD, 20).build());
	}

	/** Sends one request and notes it (the panel then shows its answer). */
	void sent(final boolean ok, final RemoteHack hack) {
		this.sentAt = Util.getMillis();
		this.status(ok ? Component.translatableWithFallback("cyberblock.rhack.sending", "%s: sending...", Component.translatableWithFallback(hack.langKey(),
			hack.english())) : Component.translatableWithFallback("cyberblock.rhack.offline", "This server has no Cyberblock link"), ok ? McTheme.PANEL_TEXT : McTheme.RED);
	}

	void status(final Component text, final int colour) {
		this.status = text;
		this.statusColour = colour;
	}

	static Component hackName(final RemoteHack hack) {
		return Component.translatableWithFallback(hack.langKey(), hack.english());
	}

	/** The host line of a panel: "Host: name (district)", or that no Cyberpunk host is linked. */
	static Component hostLabel(final NetState.@Nullable Host host) {
		if (host == null) {
			return Component.translatableWithFallback("cyberblock.hack.netmap.no_host", "No linked Cyberpunk host");
		}

		String where = host.district().isEmpty() ? "" : " (" + host.district() + ")";
		return Component.translatableWithFallback("cyberblock.hack.netmap.host", "Host: %s", host.name() + where);
	}

	@Override
	public void tick() {
		// the host list and districts every 2 s while open (the first at once)
		if (this.ticks++ % 40 == 0) {
			ActionsClient.requestState();
		}

		ActionsClient.Result r = ActionsClient.lastSince(this.sentAt);
		if (r != null) {
			RemoteHack hack = RemoteHack.byWire(r.hack());
			Component name = hack == null ? Component.literal(r.hack()) : hackName(hack);
			this.status(r.ok() ? Component.translatableWithFallback("cyberblock.rhack.done", "%s: done", name)
				: Component.translatableWithFallback("cyberblock.rhack.failed", "%s failed: %s", name, r.reason()), r.ok() ? McTheme.GREEN : McTheme.RED);
			this.sentAt = Long.MAX_VALUE;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		McTheme.panel(graphics, this.left(), this.top(), this.panelW, this.panelH);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.text(this.font, this.title, this.left() + PAD, this.top() + 7, McTheme.PANEL_TEXT, false);
		int y = this.top() + this.panelH - 14;
		graphics.text(this.font, this.font.plainSubstrByWidth(this.status.getString(), this.panelW - 2 * PAD), this.left() + PAD, y, this.statusColour, false);
	}
}
