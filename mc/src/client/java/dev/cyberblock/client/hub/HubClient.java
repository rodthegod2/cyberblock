package dev.cyberblock.client.hub;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.net.ClientNet;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.hub.Afterlife;
import dev.cyberblock.hub.HubBlocks;
import dev.cyberblock.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;

/**
 * The client half of the Afterlife hub (megaplan 7-H1): while this player is in the hub, Cyberpunk does not drive Steve
 * ({@link HostState#released}: Minecraft is the whole picture there and V is parked); the pause screens (Minecraft's and the Night
 * City one) get an "Afterlife hub" button, "Leave the Afterlife" inside it, which sends {@code /cyberblock hub} (the server decides);
 * and the leaderboard boards draw their scores. The button shows only on a server with the hub ({@code hs_ack.features} bit 16). Log
 * lines: {@code hub: in the Afterlife (Cyberpunk does not drive Steve here)}, {@code hub: back from the Afterlife}.
 */
public final class HubClient implements ClientModInitializer {
	private static boolean inHub;

	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(HubBlocks.LEADERBOARD_ENTITY, LeaderboardRenderer::new);
		ClientTickEvents.END_CLIENT_TICK.register(HubClient::tick);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (paused(screen) && (ClientNet.features() & Payloads.FEATURE_HUB) != 0 && minecraft.player != null) {
				Screens.getWidgets(screen).add(Button.builder(label(inHub), b -> go(minecraft)).bounds(width - 102, 4, 98, 20).build());
			}
		});
	}

	private static void tick(final Minecraft minecraft) {
		boolean now = minecraft.level != null && Afterlife.is(minecraft.level);
		if (now != inHub) {
			inHub = now;
			HostState.released = now;
			Cyberblock.LOG.info(now ? "hub: in the Afterlife (Cyberpunk does not drive Steve here)" : "hub: back from the Afterlife");
		}
	}

	private static boolean paused(final Screen screen) {
		return screen instanceof PauseScreen pause && pause.showsPauseMenu() || screen instanceof NightCityPauseScreen;
	}

	static Component label(final boolean inside) {
		return inside ? Component.translatableWithFallback("cyberblock.hub.button.leave", "Leave the Afterlife")
			: Component.translatableWithFallback("cyberblock.hub.button.enter", "Afterlife hub");
	}

	private static void go(final Minecraft minecraft) {
		minecraft.gui.setScreen(null);
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand("cyberblock hub");
		}
	}
}
