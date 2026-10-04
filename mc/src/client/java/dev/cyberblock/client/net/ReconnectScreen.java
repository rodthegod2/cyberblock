package dev.cyberblock.client.net;

import dev.cyberblock.client.ui.McTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * "Reconnecting to &lt;server&gt; in N s" with Cancel (megaplan section 1.5 step 5), shown in place of the disconnect screen. When the
 * time is up it connects again the vanilla way; Cancel goes back to the disconnect screen it replaced.
 */
final class ReconnectScreen extends Screen {
	private final Screen disconnected;
	private final ServerData server;
	private final long retryAtNanos;

	ReconnectScreen(final Screen disconnected, final ServerData server, final int delaySeconds) {
		super(Component.translatableWithFallback("cyberblock.reconnect.title", "Connection lost"));
		this.disconnected = disconnected;
		this.server = server;
		this.retryAtNanos = System.nanoTime() + delaySeconds * 1_000_000_000L;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.reconnect.cancel", "Cancel"), b -> this.onClose())
			.bounds(this.width / 2 - 100, this.height / 2 + 24, 200, 20).build());
	}

	private int secondsLeft() {
		return (int) Math.max(0L, (this.retryAtNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
	}

	@Override
	public void tick() {
		if (this.secondsLeft() == 0) {
			ClientNet.retrying();
			ConnectScreen.startConnecting(this.disconnected, this.minecraft, ServerAddress.parseString(this.server.ip), this.server, false, null);
		}
	}

	@Override
	public void onClose() {
		ClientNet.cancelReconnect();
		this.minecraft.gui.setScreen(this.disconnected);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 24, McTheme.WHITE);
		graphics.centeredText(this.font, Component.translatableWithFallback("cyberblock.reconnect.in", "Reconnecting to %s in %s s",
			this.server.name, this.secondsLeft()), this.width / 2, this.height / 2 - 8, McTheme.WHITE);
	}
}
