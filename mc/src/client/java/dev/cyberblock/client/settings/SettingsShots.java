package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.settings.CyberblockSettingsScreen.Page;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Developer screenshots of the settings screen (megaplan 9-S1 "Done: screenshots of every tab"): only when the environment variable
 * CYBERBLOCK_SETTINGS_SHOTS is set (a dev client started by the 9S check, never in a player's game). Ten seconds into a world it opens
 * the screen on each tab in turn and saves {@code 9s_<tab>.png} into the run folder's screenshots; then it changes two server values
 * through the screen's own command path (guest spawns on, Heat scaling 150 %) and shoots the Guests and Difficulty tabs again
 * ({@code 9s_guests_changed}, {@code 9s_difficulty_changed}) with the server's answer. Client thread.
 */
final class SettingsShots {
	private static final int SETTLE = 30;
	private static boolean enabled;
	private static int ticks;
	private static int step;

	private SettingsShots() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_SETTINGS_SHOTS") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_SETTINGS_SHOTS is set: the settings screenshot tour runs 10 s into a world");
		}
	}

	static void tick(final Minecraft minecraft) {
		if (!enabled || minecraft.player == null || step < 0) {
			return;
		}

		ticks++;
		Page[] pages = Page.values();
		if (step == 0) {
			if (ticks >= 200) {
				next();
			}
		} else if (step <= pages.length * 2) {
			// odd steps open a tab, even steps shoot it once the server's values are in
			Page p = pages[(step - 1) / 2];
			if (step % 2 == 1) {
				CyberblockSettingsScreen.open(null, p);
				next();
			} else if (ticks >= SETTLE) {
				shot(minecraft, "9s_" + p.id());
				next();
			}
		} else {
			int at = step - pages.length * 2;
			switch (at) {
				case 1 -> {
					CyberblockSettingsScreen.open(null, Page.GUESTS);
					ServerValues.command("cyberblock guests spawns on");
					next();
				}
				case 2 -> wait(() -> shot(minecraft, "9s_guests_changed"));
				case 3 -> {
					CyberblockSettingsScreen.open(null, Page.DIFFICULTY);
					ServerValues.command("cyberblock difficulty heat 150");
					next();
				}
				case 4 -> wait(() -> shot(minecraft, "9s_difficulty_changed"));
				case 5 -> wait(() -> {
					minecraft.gui.setScreen(null);
					Cyberblock.LOG.info("shots: done");
					step = -2;
				});
				default -> {
				}
			}
		}
	}

	private static void wait(final Runnable action) {
		if (ticks >= SETTLE) {
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
