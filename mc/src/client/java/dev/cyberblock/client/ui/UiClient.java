package dev.cyberblock.client.ui;

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
		ClientTickEvents.END_CLIENT_TICK.register(UiClient::tick);
	}

	private static void tick(final Minecraft minecraft) {
		UiLink.tick();
		Waypoints.tick(minecraft);
	}
}
