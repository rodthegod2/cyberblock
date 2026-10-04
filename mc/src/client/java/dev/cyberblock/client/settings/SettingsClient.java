package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * The client half of {@code /cyberblock debug} (plan Q-B3) and {@code /cyberblock settings} (Q-B5): the chat screen's command is
 * handled here before it is sent, so both work on any server (the server registers the same literals for completion, and to answer
 * a client without the mod). Also the "Cyberblock..." button on the vanilla pause screen.
 */
public final class SettingsClient {
	/** {@code /cyberblock settings} was typed: the screen opens on the next tick, once the chat screen has closed itself. */
	private static boolean openSettings;

	private SettingsClient() {
	}

	public static void init() {
		ClientSendMessageEvents.ALLOW_COMMAND.register(SettingsClient::command);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (openSettings) {
				openSettings = false;
				CyberblockSettingsScreen.open(null);
			}
		});
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatableWithFallback("cyberblock.settings.button", "Cyberblock..."),
					b -> CyberblockSettingsScreen.open(screen)).bounds(4, 4, 98, 20).build());
			}
		});
	}

	/** False when this client handled the command (it is not sent). */
	private static boolean command(final String command) {
		if (command.trim().equals("cyberblock settings")) {
			openSettings = true;
			return false;
		}

		DebugFlags.Switch flip = DebugFlags.parse(command);
		if (flip == null) {
			return true;
		}

		Minecraft minecraft = Minecraft.getInstance();
		boolean on = flip.on() == null ? DebugFlags.get(flip.flag()) : flip.on();
		DebugFlags.set(flip.flag(), on);
		Cyberblock.LOG.info("debug: {} {}", flip.flag(), on ? "on" : "off");
		if (minecraft.player != null) {
			String key = "cyberblock.debug." + flip.flag() + "." + (on ? "on" : "off");
			minecraft.player.sendSystemMessage(Component.translatableWithFallback(key, "Cyberblock debug " + flip.flag() + ": " + (on ? "on" : "off")));
		}

		return false;
	}
}
