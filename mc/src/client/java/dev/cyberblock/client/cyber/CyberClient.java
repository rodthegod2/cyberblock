package dev.cyberblock.client.cyber;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** Layer 6 (plan 2026-10-03, track C), client half: keys, the RAM bar, scanning, the skill tree. */
public class CyberClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RamHud.ID, RamHud::draw);
	}
}
