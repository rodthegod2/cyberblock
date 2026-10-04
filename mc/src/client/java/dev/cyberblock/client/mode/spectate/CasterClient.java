package dev.cyberblock.client.mode.spectate;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.ui.CasterHud;
import dev.cyberblock.client.ui.HeatMeterHud;
import dev.cyberblock.mode.spectate.CasterWire;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** The client side of spectator and caster mode (megaplan 8-T3): {@code cyberblock:ui caster} feeds the caster overlay. */
public final class CasterClient {
	private static int bad;

	private CasterClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(CasterWire.T, CasterClient::onUi);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, CasterHud.ID, CasterHud::draw);
		HeatMeterHud.hidden = CasterHud::active; // the overlay shows the meter itself
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> CasterHud.view = CasterWire.View.OFF);
		// dev only (CYBERBLOCK_SHOTS_8T): the Phase 8T screenshot tour
		ShotTour.init();
		ClientTickEvents.END_CLIENT_TICK.register(ShotTour::tick);
	}

	static void onUi(final String json) {
		CasterWire.View v = CasterWire.parse(json);
		if (v == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("caster: bad overlay data dropped ({} so far)", bad);
			}

			return;
		}

		CasterHud.view = v;
	}
}
