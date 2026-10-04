package dev.cyberblock.client.mode.heat;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.ui.HeatMeterHud;
import dev.cyberblock.mode.heat.HeatWire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** The client side of Server Heat (megaplan 8-T1): {@code cyberblock:ui heat} feeds the HUD meter; leaving a server clears it. */
public final class HeatClient {
	private static int bad;

	private HeatClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(HeatWire.T, HeatClient::onUi);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HeatMeterHud.ID, HeatMeterHud::draw);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> HeatMeterHud.meter = HeatWire.Meter.NONE);
	}

	static void onUi(final String json) {
		HeatWire.Meter m = HeatWire.parse(json);
		if (m == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("heat: bad meter data dropped ({} so far)", bad);
			}

			return;
		}

		HeatMeterHud.meter = m;
	}
}
