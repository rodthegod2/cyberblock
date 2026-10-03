package dev.cyberblock.client.cyber;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;

/** Layer 6 (plan 2026-10-03, track C), client half: keys, the RAM bar, scanning, the skill tree. */
public class CyberClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CyberKeys.register();
		CyberLink.register();
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RamHud.ID, RamHud::draw);
		ClientTickEvents.END_CLIENT_TICK.register(CyberClient::tick);
	}

	private static void tick(final Minecraft minecraft) {
		CyberKeys.tick(minecraft);
	}
}
