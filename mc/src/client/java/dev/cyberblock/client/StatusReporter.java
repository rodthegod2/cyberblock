package dev.cyberblock.client;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Tells the host how Steve is: every 2 client ticks (10 Hz), and at once when anything changed. It doubles as the host's watchdog. */
public final class StatusReporter {
	/**
	 * The Night City pause screen is open (plan retest3 section 1.5): sent as status.pause, and Cyberpunk stays paused exactly while it
	 * is true and the link is up. The UI track (W) sets it.
	 */
	public static volatile boolean nightCityPaused;
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

		String status = format(player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(),
			minecraft.gameMode.getPlayerMode().getName(), minecraft.gui.screen() != null, nightCityPaused);
		if (!status.equals(last) || ++ticksSinceSent >= 2) {
			last = status;
			ticksSinceSent = 0;
			HostLink.send(status);
		}
	}

	/** The status message (shared/protocol.md); pause is the plan retest3 extension. */
	static String format(final float hp, final float maxHp, final int food, final String gameMode, final boolean screen, final boolean pause) {
		return String.format(Locale.ROOT, "{\"t\":\"status\",\"hp\":%.1f,\"maxHp\":%.1f,\"food\":%d,\"gm\":\"%s\",\"screen\":%b,\"pause\":%b}",
			hp, maxHp, food, gameMode, screen, pause);
	}
}
