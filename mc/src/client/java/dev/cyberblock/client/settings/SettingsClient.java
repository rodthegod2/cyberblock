package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The client half of {@code /cyberblock debug} (plan Q-B3): the chat screen's command is handled here before it is sent, so the
 * switches work on any server. The server registers the same literals (for completion, and to answer a client without the mod).
 */
public final class SettingsClient {
	private SettingsClient() {
	}

	public static void init() {
		ClientSendMessageEvents.ALLOW_COMMAND.register(SettingsClient::command);
	}

	/** False when this client handled the command (it is not sent). */
	private static boolean command(final String command) {
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
