package dev.cyberblock.client;

import dev.cyberblock.Cyberblock;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Tells the host how Steve is: every 2 client ticks (10 Hz), and at once when anything changed. It doubles as the host's watchdog. */
final class StatusReporter {
	private static String last = "";
	private static int ticksSinceSent;

	private StatusReporter() {
	}

	/** Every client tick. Nothing is sent while there is no world: no status tells the host Minecraft isn't playing. */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) {
			return;
		}

		String status = String.format(Locale.ROOT, "{\"t\":\"status\",\"hp\":%.1f,\"maxHp\":%.1f,\"food\":%d,\"gm\":\"%s\",\"screen\":%b}",
			player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(), minecraft.gameMode.getPlayerMode().getName(),
			minecraft.gui.screen() != null);
		if (!status.equals(last) || ++ticksSinceSent >= 2) {
			last = status;
			ticksSinceSent = 0;
			Cyberblock.events.accept(status);
		}
	}
}
