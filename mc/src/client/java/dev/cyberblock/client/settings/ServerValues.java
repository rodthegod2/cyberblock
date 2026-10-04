package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.net.Payloads;
import dev.cyberblock.settings.SettingsWire;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the server decided for the settings screen (megaplan 9-S1): asked with {@code cyberblock:act settings}, answered with
 * {@code cyberblock:ui settings} ({@link SettingsWire}). The screen changes a server value only by sending its {@code /cyberblock}
 * command and asking again, so what it shows is always the server's answer. Client thread.
 *
 * <p>Commands are paced: vanilla's server adds 20 to a player's command counter per command, takes 1 off per tick and kicks a
 * player who is not an operator at 200 (command-spam-threshold-seconds 10), so ten quick clicks would kick them. Each row keeps only
 * its latest command, and one command leaves every {@link #GAP_NS} (a little over the second the server needs to forget it).
 */
final class ServerValues {
	static final long GAP_NS = 1_100_000_000L;

	private static SettingsWire.@Nullable Snapshot snapshot;
	/** The connection's first answer is logged (which path is active: the server's values, or none). */
	private static boolean logged;
	private static boolean warnedNoChannel;
	/** Row (the command without its last word, the value) to its latest command, oldest row first. */
	private static final Map<String, String> queued = new LinkedHashMap<>();
	/** Commands sent and not yet answered (the next snapshot answers them: the server reads the act after the command). */
	private static final Map<String, String> sent = new HashMap<>();
	private static long lastSent = System.nanoTime() - GAP_NS;

	private ServerValues() {
	}

	static void init() {
		UiRoutes.listen(SettingsWire.T, ServerValues::received);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			snapshot = null;
			logged = false;
			warnedNoChannel = false;
			queued.clear();
			sent.clear();
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

	/** Queues a server setting's command (its row's earlier unsent one is replaced); it is sent at once if the last left long enough ago. */
	static void command(final String command) {
		if (Minecraft.getInstance().player == null) {
			return;
		}

		queued.put(row(command), command);
		tick();
		refresh();
	}

	/** The command waiting or unanswered for this command's row ({@code null}: none, the row shows the server's value). */
	static @Nullable String pending(final String command) {
		String row = row(command);
		String waiting = queued.get(row);
		return waiting != null ? waiting : sent.get(row);
	}

	/** Every client tick: sends the oldest queued command once the gap has passed, then asks for the values again. */
	static void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (queued.isEmpty() || System.nanoTime() - lastSent < GAP_NS || minecraft.player == null) {
			return;
		}

		Iterator<Map.Entry<String, String>> it = queued.entrySet().iterator();
		Map.Entry<String, String> next = it.next();
		it.remove();
		lastSent = System.nanoTime();
		sent.put(next.getKey(), next.getValue());
		Cyberblock.LOG.info("settings: /{}{}", next.getValue(), queued.isEmpty() ? "" : " (" + queued.size() + " more waiting, one a second)");
		minecraft.player.connection.sendCommand(next.getValue());
		request();
	}

	/** The row a command belongs to: every row's command ends with its value. */
	private static String row(final String command) {
		int space = command.lastIndexOf(' ');
		return space < 0 ? command : command.substring(0, space);
	}

	private static void refresh() {
		if (Minecraft.getInstance().gui.screen() instanceof CyberblockSettingsScreen screen) {
			screen.apply(snapshot);
		}
	}

	private static void received(final String json) {
		SettingsWire.Snapshot s = SettingsWire.parse(json);
		if (s == null) {
			Cyberblock.LOG.warn("settings: the server's values were not a JSON object; ignored");
			return;
		}

		SettingsWire.Snapshot before = snapshot;
		snapshot = s;
		sent.clear();
		if (!logged) {
			logged = true;
			Cyberblock.LOG.info("settings: server values received (admin {}, host {}, server.json rows {})", s.admin() ? "yes" : "no",
				s.host() ? "yes" : "no", s.world() == null ? "hidden" : "shown");
		}

		if (!s.equals(before)) {
			Cyberblock.LOG.info("settings: server values: admin {}, guests {} devices {}, body {}, pvp {}, pin {}, erase {}, overlord {}, heat {} %",
				s.admin() ? "yes" : "no", s.perms(), s.devices(), s.body(), s.pvp() ? "on" : "off", s.hidePin() ? "hidden" : "shown", s.erase() ? "on" : "off",
				s.overlord().key(), s.heat());
		}

		refresh();
	}
}
