package dev.cyberblock.client.hack.screen;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The Traffic Tool (megaplan task 6-I1, an item): switch the traffic lights of a host's district, or gridlock the road where the
 * player points (the cars of that road segment stop for a minute). The host is picked with the host button (the Net Map shares
 * the choice); the server checks the owner's `devices` toggle and its traffic bit.
 */
final class TrafficToolScreen extends ToolScreen {
	private Button host;

	TrafficToolScreen() {
		super(Component.translatableWithFallback("cyberblock.hack.traffic.title", "Traffic Tool"), 220, 20 + 3 * ROW + 52);
	}

	@Override
	protected void init() {
		this.host = this.row(0, ToolScreen.hostLabel(ActionsClient.selectedHost()), b -> {
			NetState.Host next = ActionsClient.state().next(ActionsClient.selected);
			ActionsClient.selected = next == null ? null : next.id();
		});
		this.row(1, Component.translatableWithFallback("cyberblock.hack.traffic.switch", "Switch the lights"), b -> {
			NetState.Host h = ActionsClient.selectedHost();
			if (h != null) {
				this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.district(RemoteHack.TRAFFIC_SWITCH, h.id(), 0)), RemoteHack.TRAFFIC_SWITCH);
			}
		});
		this.row(2, Component.translatableWithFallback("cyberblock.hack.traffic.gridlock", "Gridlock this road"),
			b -> this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.point(RemoteHack.GRIDLOCK, ActionsClient.aimedPoint(this.minecraft))), RemoteHack.GRIDLOCK));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.host != null) {
			this.host.setMessage(ToolScreen.hostLabel(ActionsClient.selectedHost()));
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Vec3 p = ActionsClient.aimedPoint(this.minecraft);
		Component where = Component.translatableWithFallback("cyberblock.hack.traffic.here", "Here: %s", String.format(Locale.ROOT, "%.0f %.0f %.0f", p.x, p.y, p.z));
		graphics.text(this.font, where, this.left() + PAD, this.top() + 20 + 3 * ROW + 4, McTheme.DARK_GRAY, false);
	}
}
