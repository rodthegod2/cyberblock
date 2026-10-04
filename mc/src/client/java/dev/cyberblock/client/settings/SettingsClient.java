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
 * a client without the mod). Also the "Cyberblock..." button on the vanilla pause screen, and the settings screen's server values, Performance
 * values and screenshot tour (megaplan 9-S1).
 */
public final class SettingsClient {
	/** {@code /cyberblock settings} was typed: the screen opens on the next tick, once the chat screen has closed itself. */
	private static boolean openSettings;
	private static boolean toldPerformance;

	private SettingsClient() {
	}

	public static void init() {
		ServerValues.init();
		PerformancePrefs.load();
		SettingsShots.init();
		ClientSendMessageEvents.ALLOW_COMMAND.register(SettingsClient::command);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (openSettings) {
				openSettings = false;
				CyberblockSettingsScreen.open(null);
			}

			if (!toldPerformance) {
				toldPerformance = true;
				performanceLine();
			}

			SettingsShots.tick(minecraft);
		});
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatableWithFallback("cyberblock.settings.button", "Cyberblock..."),
					b -> CyberblockSettingsScreen.open(screen)).bounds(4, 4, 98, 20).build());
			}
		});
	}

	/**
	 * Once, on the first client tick (every reader has registered by then): which parts of the game apply the Performance tab's values,
	 * so a log shows whether Performance mode is really active.
	 */
	private static void performanceLine() {
		PerformancePrefs p = PerformancePrefs.current();
		String readers = PerformancePrefs.readers();
		Cyberblock.LOG.info("settings: performance frame scale {} %, figure cap {} (config/cyberblock/performance.json); {}", p.scale(), p.figureCap(),
			readers.isEmpty() ? "nothing applies them yet: frames export at full size and the figure cap stays the built-in one" : "applied by " + readers);
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
