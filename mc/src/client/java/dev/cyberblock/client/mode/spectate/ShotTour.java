package dev.cyberblock.client.mode.spectate;

import dev.cyberblock.Cyberblock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Developer screenshots of Phase 8T (megaplan 8-T2/8-T3 "Done: screenshots"): only when the environment variable
 * CYBERBLOCK_SHOTS_8T is set (a dev client started by hand; never in a player's game). In a singleplayer world it sets Server Heat to
 * 85, takes damage until it goes down, closes the downed screen, calls Trauma Team (with three credchips it gives itself), waits for the
 * flyer, then spectates, saving one screenshot per step into the run folder's screenshots (8t_*.png). Client thread.
 */
final class ShotTour {
	private static final int STEP = 60;
	private static boolean enabled;
	private static int ticks = -1;
	private static int step;
	private static int at;

	private ShotTour() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_SHOTS_8T") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_SHOTS_8T is set: the Heat, Downed and caster screenshot tour runs in the world");
		}
	}

	static void tick(final Minecraft minecraft) {
		if (!enabled || minecraft.player == null || step < 0) {
			return;
		}

		ticks++;
		int wait = step == 6 ? 440 : STEP; // the flyer lands 30 s (600 ticks) after the call: shoot it 2 s before, on its way down
		if (step == 0 ? ticks < 200 : ticks - at < wait) {
			return;
		}

		switch (step) {
			case 0 -> {
				command(minecraft, "gamemode survival");
				command(minecraft, "give @s cyberblock:eddie_chip 3");
				command(minecraft, "cyberblock heat set 85");
			}
			case 1 -> {
				shot(minecraft, "8t_heat_meter");
				command(minecraft, "damage @s 50");
			}
			case 2 -> {
				shot(minecraft, "8t_downed_screen");
				minecraft.gui.setScreen(null);
			}
			case 3 -> {
				shot(minecraft, "8t_downed_hud");
				command(minecraft, "cyberblock trauma");
			}
			case 4 -> shot(minecraft, "8t_trauma_called");
			case 5 -> minecraft.player.setXRot(-55.0F); // look up at the flyer's light column
			case 6 -> shot(minecraft, "8t_trauma_flyer");
			case 7 -> {
				minecraft.player.setXRot(0.0F);
				command(minecraft, "scoreboard objectives add cb_chaos dummy \"Chaos\"");
				command(minecraft, "scoreboard players set CbGuest2 cb_chaos 17");
				command(minecraft, "scoreboard players set CbHost2 cb_chaos 9");
				command(minecraft, "cyberblock spectate");
			}
			case 8 -> {
				shot(minecraft, "8t_caster_overlay");
				command(minecraft, "cyberblock spectate stop");
				command(minecraft, "cyberblock heat clear");
				Cyberblock.LOG.info("shots: done");
				step = -1;
				return;
			}
			default -> {
			}
		}

		step++;
		at = ticks;
	}

	private static void command(final Minecraft minecraft, final String command) {
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand(command);
		}
	}

	private static void shot(final Minecraft minecraft, final String name) {
		Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> Cyberblock.LOG.info("shots: {} ({})", name, message.getString()));
	}
}
