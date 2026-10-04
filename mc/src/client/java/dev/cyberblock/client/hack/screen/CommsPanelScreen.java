package dev.cyberblock.client.hack.screen;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.actions.ActWire;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The Comms Panel (megaplan task 6-I1, an item): a short message (at most 64 characters) and/or a waypoint where the player points,
 * for one host. The host's client shows it as a toast and a locator-bar waypoint on its Minecraft HUD, which is the HUD the host
 * sees over Cyberpunk. The server rate-limits it and answers on the action bar.
 */
final class CommsPanelScreen extends ToolScreen {
	private Button host;
	private EditBox message;

	CommsPanelScreen() {
		super(Component.translatableWithFallback("item.cyberblock.comms_panel", "Comms Panel"), 240, 20 + 4 * ROW + 40);
	}

	@Override
	protected void init() {
		this.host = this.row(0, ToolScreen.hostLabel(ActionsClient.selectedHost()), b -> {
			NetState.Host next = ActionsClient.state().next(ActionsClient.selected);
			ActionsClient.selected = next == null ? null : next.id();
		});
		this.message = new EditBox(this.font, this.left() + PAD, this.top() + 20 + ROW + 1, this.panelW() - 2 * PAD, 18,
			Component.translatableWithFallback("cyberblock.comms.message", "Message"));
		this.message.setMaxLength(ActWire.MAX_TEXT);
		this.message.setHint(Component.translatableWithFallback("cyberblock.comms.hint", "Short message (64 characters)"));
		this.addRenderableWidget(this.message);
		this.row(2, Component.translatableWithFallback("cyberblock.comms.send", "Send message"), b -> this.send(false));
		this.row(3, Component.translatableWithFallback("cyberblock.comms.send_waypoint", "Send message and waypoint here"), b -> this.send(true));
		this.setInitialFocus(this.message);
	}

	private void send(final boolean waypoint) {
		NetState.Host h = ActionsClient.selectedHost();
		String text = this.message.getValue().strip();
		if (h == null || text.isEmpty() && !waypoint) {
			return;
		}

		Vec3 p = ActionsClient.aimedPoint(this.minecraft);
		int[] at = waypoint ? new int[] {(int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)} : null;
		boolean ok = ActionsClient.send(ActWire.T_COMMS, ActWire.comms(h.id(), text, at));
		this.status(ok ? Component.translatableWithFallback("cyberblock.comms.sending", "Sending to %s...", h.name())
			: Component.translatableWithFallback("cyberblock.rhack.offline", "This server has no Cyberblock link"), ok ? McTheme.PANEL_TEXT : McTheme.RED);
		if (ok) {
			this.message.setValue("");
		}
	}

	/** Sets the message box (the dev screenshot tour types one). */
	void type(final String text) {
		this.message.setValue(text);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.host != null) {
			this.host.setMessage(ToolScreen.hostLabel(ActionsClient.selectedHost()));
		}
	}
}
