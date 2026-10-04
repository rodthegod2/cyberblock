package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.net.Payloads;
import dev.cyberblock.settings.SettingsWire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the server decided for the settings screen (megaplan 9-S1): asked with {@code cyberblock:act settings}, answered with
 * {@code cyberblock:ui settings} ({@link SettingsWire}). The screen changes a server value only by sending its {@code /cyberblock}
 * command and asking again, so what it shows is always the server's answer. Client thread.
 */
final class ServerValues {
	private static SettingsWire.@Nullable Snapshot snapshot;
	/** The connection's first answer is logged (which path is active: the server's values, or none). */
	private static boolean logged;
	private static boolean warnedNoChannel;

	private ServerValues() {
	}

	static void init() {
		UiRoutes.listen(SettingsWire.T, ServerValues::received);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			snapshot = null;
			logged = false;
			warnedNoChannel = false;
		});
	}

	static SettingsWire.@Nullable Snapshot snapshot() {
		return snapshot;
	}

	/** Asks the server for its values (the answer comes at the end of the server's tick). */
	static void request() {
		if (Minecraft.getInstance().player == null) {
			return;
		}

		if (!ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			if (!warnedNoChannel) {
				warnedNoChannel = true;
				Cyberblock.LOG.info("settings: this server has no Cyberblock channel: its values cannot be shown or changed here");
			}

			return;
		}

		ClientPlayNetworking.send(new Payloads.Act(SettingsWire.T, "{}"));
	}

	/** Sends a server setting's command (the server decides) and asks for the values again. */
	static void command(final String command) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		Cyberblock.LOG.info("settings: /{}", command);
		minecraft.player.connection.sendCommand(command);
		request();
	}

	private static void received(final String json) {
		SettingsWire.Snapshot s = SettingsWire.parse(json);
		if (s == null) {
			Cyberblock.LOG.warn("settings: the server's values were not a JSON object; ignored");
			return;
		}

		SettingsWire.Snapshot before = snapshot;
		snapshot = s;
		if (!logged) {
			logged = true;
			Cyberblock.LOG.info("settings: server values received (admin {}, host {}, server.json rows {})", s.admin() ? "yes" : "no",
				s.host() ? "yes" : "no", s.world() == null ? "hidden" : "shown");
		}

		if (!s.equals(before)) {
			Cyberblock.LOG.info("settings: server values: guests {} devices {}, body {}, pvp {}, pin {}, erase {}, overlord {}, heat {} %", s.perms(), s.devices(),
				s.body(), s.pvp() ? "on" : "off", s.hidePin() ? "hidden" : "shown", s.erase() ? "on" : "off", s.overlord().key(), s.heat());
		}

		if (Minecraft.getInstance().gui.screen() instanceof CyberblockSettingsScreen screen) {
			screen.apply(s);
		}
	}
}
