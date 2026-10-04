package dev.cyberblock.client.mode.pvphack;

import dev.cyberblock.Cyberblock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

/**
 * Developer screenshots of player hacks (megaplan 8-X1 "screenshots of the glitch overlay"): only when the environment variable
 * CYBERBLOCK_PVPHACK_SHOTS is set (a dev client started by hand with devtools, never in a player's game). Ten seconds into a world it
 * fires Reboot Optics at its own player through the dev command ({@code /cyberblock test pvphack reboot_optics <self>}; the server
 * must allow PvP), shoots the Intrusion warning with its short Breach, aborts the Breach so the hack lands, and shoots the glitch
 * overlay early and late; then Memory Wipe on three diamonds it gives itself (they drop at its feet) and Short Circuit. Files: the
 * run folder's screenshots/8x_*.png. Client thread.
 */
final class PvpShots {
	private static boolean enabled;
	private static int ticks;
	private static int step;

	private PvpShots() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_PVPHACK_SHOTS") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_PVPHACK_SHOTS is set: the player hack screenshot tour runs 10 s into a world");
		}
	}

	static void tick(final Minecraft minecraft) {
		if (!enabled || minecraft.player == null || step < 0) {
			return;
		}

		ticks++;
		switch (step) {
			case 0 -> {
				if (ticks >= 200) {
					minecraft.player.connection.sendCommand("cyberblock test pvphack reboot_optics " + minecraft.player.getName().getString());
					next();
				}
			}
			case 1 -> wait(minecraft, 30, () -> shot(minecraft, "8x_intrusion"));
			case 2 -> wait(minecraft, 10, () -> closeScreen(minecraft));
			case 3 -> wait(minecraft, 8, () -> shot(minecraft, "8x_glitch_early"));
			case 4 -> wait(minecraft, 30, () -> shot(minecraft, "8x_glitch_mid"));
			case 5 -> wait(minecraft, 25, () -> {
				shot(minecraft, "8x_glitch_late");
				minecraft.player.connection.sendCommand("give " + self(minecraft) + " minecraft:diamond 3");
			});
			// Memory Wipe on the held diamonds: the Breach aborted, they drop at the feet (the server log says so)
			case 6 -> wait(minecraft, 60, () -> minecraft.player.connection.sendCommand("cyberblock test pvphack memory_wipe " + self(minecraft)));
			case 7 -> wait(minecraft, 20, () -> closeScreen(minecraft));
			case 8 -> wait(minecraft, 20, () -> shot(minecraft, "8x_memory_wipe"));
			// Short Circuit: stun and damage
			case 9 -> wait(minecraft, 40, () -> minecraft.player.connection.sendCommand("cyberblock test pvphack short_circuit " + self(minecraft)));
			case 10 -> wait(minecraft, 20, () -> closeScreen(minecraft));
			case 11 -> wait(minecraft, 10, () -> {
				shot(minecraft, "8x_short_circuit");
				Cyberblock.LOG.info("shots: player hack tour done");
				step = -2;
			});
			default -> {
			}
		}
	}

	/** The Breach aborted: the hack lands. */
	private static void closeScreen(final Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (screen != null) {
			screen.onClose();
		}
	}

	private static String self(final Minecraft minecraft) {
		return minecraft.player.getName().getString();
	}

	/** After `delay` ticks in this step: run it and go on. */
	private static void wait(final Minecraft minecraft, final int delay, final Runnable action) {
		if (ticks >= delay) {
			action.run();
			next();
		}
	}

	private static void next() {
		step++;
		ticks = 0;
	}

	private static void shot(final Minecraft minecraft, final String name) {
		Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> Cyberblock.LOG.info("shots: {} ({})", name, message.getString()));
	}
}
