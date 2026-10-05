package dev.cyberblock.client.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.settings.CyberblockSettingsScreen.Page;
import dev.cyberblock.settings.SettingsWire;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.input.KeyEvent;

/**
 * Developer screenshots of the settings screen (megaplan 9-S1 "Done: screenshots of every tab"): only when the environment variable
 * CYBERBLOCK_SETTINGS_SHOTS is set (a dev client started by the 9S check, never in a player's game). Ten seconds into a world it opens
 * the screen on each tab in turn and saves {@code 9s_<tab>.png} into the run folder's screenshots; then it changes two server values
 * through the screen's own command path (guest spawns on, Heat scaling 150 %) and shoots the Guests and Difficulty tabs again
 * ({@code 9s_guests_changed}, {@code 9s_difficulty_changed}) with the server's answer. Once the server says this player is no
 * longer an operator (the check deops it), it scrolls over a server row and presses every Guests row in one tick ({@link #burst}).
 * Client thread.
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
				});
				case 6 -> {
					SettingsWire.Snapshot s = ServerValues.snapshot();
					if (s != null && !s.admin()) {
						CyberblockSettingsScreen.open(null, Page.GUESTS);
						if (minecraft.gui.screen() instanceof CyberblockSettingsScreen screen) {
							burst(screen);
						}

						step = -2;
					}
				}
				default -> {
				}
			}
		}
	}

	/**
	 * As a player who is not an operator: the mouse wheel over the first server row, then every server row of the Guests tab pressed
	 * in one tick. The wheel must change nothing, and the commands must be paced so the server does not kick for command spam.
	 */
	private static void burst(final CyberblockSettingsScreen screen) {
		List<CycleButton<?>> rows = screen.serverButtons();
		CycleButton<?> first = rows.getFirst();
		Object before = first.getValue();
		for (int i = 0; i < 5; i++) {
			screen.mouseScrolled(first.getX() + 2, first.getY() + 2, 0, 1);
		}

		boolean wheelIgnored = before.equals(first.getValue());
		int pressed = 0;
		for (CycleButton<?> b : rows) {
			if (b.active) {
				b.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				pressed++;
			}
		}

		Cyberblock.LOG.info("shots: burst: wheel over a server row ignored: {}; pressed {} server rows", wheelIgnored ? "yes" : "no", pressed);
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
