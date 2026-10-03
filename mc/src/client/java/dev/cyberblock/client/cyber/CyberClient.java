package dev.cyberblock.client.cyber;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.QuickhackItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Layer 6 (plan 2026-10-03, track C; plan retest2 package C), client half: keys, the RAM bar, scanning, the skill tree, cyberware. */
public class CyberClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		QuickhackItem.readerHasCheapHacks = () -> {
			LocalPlayer player = Minecraft.getInstance().player;
			return player != null && CyberData.has(player, Perk.CHEAP_HACKS);
		};
		CyberKeys.register();
		CyberLink.register();
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RamHud.ID, RamHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, CyberwareHud.ID, CyberwareHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ScanClient.HUD_ID, ScanClient::draw);
		ClientTickEvents.END_CLIENT_TICK.register(CyberClient::tick);
	}

	/** Every client tick (inside vanilla's per-tick gizmo collection, which the scan and Kiroshi outlines use). */
	private static void tick(final Minecraft minecraft) {
		CyberKeys.tick(minecraft);
		ScanClient.tick(minecraft);
		CyberwareClient.tick(minecraft);
	}
}
