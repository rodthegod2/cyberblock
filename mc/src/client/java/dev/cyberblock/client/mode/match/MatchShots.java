package dev.cyberblock.client.mode.match;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.CyberwareScreen;
import dev.cyberblock.mode.match.MatchWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Developer screenshots of the match screens (megaplan 8-M1 "screenshots"): only when the environment variable
 * CYBERBLOCK_MATCH_SHOTS is set (a dev client started by hand; never in a player's game). Once the server opened the Match Terminal
 * for this player (a dev server's {@code /cyberblock test terminal}) it plays a match by itself, as clicks would, and saves 8m_*.png
 * into the run folder's screenshots: the create form, the lobby, the HUD while capturing and once captured, the running match, the
 * zone pick, the PvP buttons (Cyberware screen, chat) and the result. The zone is put so that its first capture point is under this
 * player. Client thread.
 */
final class MatchShots {
	private static final int STEP = 60;
	private static boolean enabled;
	private static int ticks;
	private static int step;
	private static int at;

	private MatchShots() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_MATCH_SHOTS") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_MATCH_SHOTS is set: the match screenshot tour runs once the Match Terminal opens");
		}
	}

	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!enabled || player == null || step < 0) {
			return;
		}

		ticks++;
		MatchWire.State s = MatchClient.state();
		if (step == 0) {
			if (minecraft.gui.screen() instanceof MatchScreen && s != null) {
				next();
			}

			return;
		}

		if (ticks - at < STEP) {
			return;
		}

		switch (step) {
			case 1 -> {
				shot(minecraft, "8m_terminal_create");
				create(player, s);
			}
			case 2 -> {
				if (s == null || s.mine() == null) {
					if (ticks - at > 600) {
						Cyberblock.LOG.info("shots: no match was created: stopping");
						step = -1;
					}

					return;
				}

				shot(minecraft, "8m_terminal_lobby");
				op("start");
			}
			case 3 -> {
				minecraft.gui.setScreen(null);
				if (ticks - at < STEP * 2) {
					return;
				}

				shot(minecraft, "8m_hud_capturing");
			}
			case 4 -> {
				MatchWire.View v = s == null ? null : s.mine();
				if (v != null && !v.points().isEmpty() && v.points().getFirst().owner() < 0 && ticks - at < 1200) {
					return;
				}

				shot(minecraft, "8m_hud_captured");
				minecraft.gui.setScreen(new MatchScreen());
			}
			case 5 -> {
				shot(minecraft, "8m_terminal_running");
				MatchWire.View v = s == null ? null : s.mine();
				if (minecraft.gui.screen() instanceof MatchScreen screen && v != null) {
					ZonePick.open(screen, v.x(), v.z(), v.r(), picked -> {
					});
				}
			}
			case 6 -> {
				shot(minecraft, "8m_zone_pick");
				minecraft.gui.setScreen(new CyberwareScreen());
			}
			case 7 -> {
				shot(minecraft, "8m_cyberware_pvp_button");
				minecraft.gui.setScreen(new ChatScreen("", false));
			}
			case 8 -> {
				shot(minecraft, "8m_hud_pvp_button");
				minecraft.gui.setScreen(new MatchScreen());
				op("end");
			}
			case 9 -> {
				shot(minecraft, "8m_terminal_result");
				minecraft.gui.setScreen(null);
				Cyberblock.LOG.info("shots: done");
				step = -1;
				return;
			}
			default -> {
			}
		}

		next();
	}

	private static void next() {
		step++;
		at = ticks;
	}

	/** Creates a match whose first capture point (0.4 r from the centre, a quarter turn round for two teams) is under the player. */
	private static void create(final LocalPlayer player, final MatchWire.State s) {
		String mode = s.modes().stream().map(MatchWire.Mode::id).filter(id -> id.endsWith(":solo")).findFirst()
			.orElse(s.modes().isEmpty() ? "cyberblock:gang_wars" : s.modes().getFirst().id());
		JsonObject o = new JsonObject();
		o.addProperty("op", "create");
		o.addProperty("mode", mode);
		JsonObject zone = new JsonObject();
		zone.addProperty("x", Math.floor(player.getX()) + 0.5);
		zone.addProperty("z", Math.floor(player.getZ()) + 0.5 - 0.4 * 16.0);
		zone.addProperty("r", 16.0);
		zone.addProperty("district", "");
		o.add("zone", zone);
		o.addProperty("time", 300);
		o.addProperty("pvp", true);
		o.addProperty("invite", false);
		MatchClient.act(o.toString());
	}

	private static void op(final String op) {
		JsonObject o = new JsonObject();
		o.addProperty("op", op);
		MatchClient.act(o.toString());
	}

	private static void shot(final Minecraft minecraft, final String name) {
		Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> Cyberblock.LOG.info("shots: {} ({})", name, message.getString()));
	}
}
