package dev.cyberblock.client.ui;

import dev.cyberblock.client.StatusReporter;
import dev.cyberblock.client.atlas.AtlasStore;
import dev.cyberblock.client.atlas.SurveyClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Package W (plan 2026-10-03-retest3-polish section 7): the Minecraft side of "every UI element is Minecraftified" (F2, F12,
 * F13). Cyberpunk keeps the game logic and its own input and streams the UI data; Minecraft draws it with its own font, sprites
 * and colours.
 */
public class UiClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		UiLink.register();
		UiHud.register();
		// megaplan 7-V1: the Holo-Table offers the host's fast-travel points
		FastTravelPoints.installHoloHook();
		// the Atlas's shared map of Night City and the survey tour (protocol.md "Atlas")
		AtlasStore.register();
		SurveyClient.register();
		ClientTickEvents.END_CLIENT_TICK.register(UiClient::tick);
	}

	private static void tick(final Minecraft minecraft) {
		Waypoints.tick(minecraft);
		if (minecraft.gui.screen() == null) {
			// every way out of the pause screen and the screens opened from it ends with no screen: Night City runs again
			StatusReporter.nightCityPaused = false;
		}
	}
}
