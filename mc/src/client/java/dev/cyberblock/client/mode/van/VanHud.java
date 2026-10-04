package dev.cyberblock.client.mode.van;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.mode.van.VanDrone;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The drone pilot's HUD (megaplan 8-V1): a camera frame, the battery bar, the distance to the Van against its leash, the aimed
 * target and the keys. Only while this client flies a drone.
 */
final class VanHud {
	static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "van_drone_hud");
	private static final int CORNER = 10;

	private VanHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		VanWire.State s = VanClient.state();
		if (!VanClient.piloting() || s == null || s.drone() == null || !(minecraft.getCameraEntity() instanceof VanDrone drone)) {
			return;
		}

		int w = graphics.guiWidth(), h = graphics.guiHeight();
		// the camera's frame corners
		int c = McTheme.fade(McTheme.AQUA, 0.7F);
		for (int[] k : new int[][] {{8, 8, 1, 1}, {w - 9, 8, -1, 1}, {8, h - 9, 1, -1}, {w - 9, h - 9, -1, -1}}) {
			graphics.fill(Math.min(k[0], k[0] + k[2] * CORNER), k[1], Math.max(k[0], k[0] + k[2] * CORNER) + 1, k[1] + 1, c);
			graphics.fill(k[0], Math.min(k[1], k[1] + k[3] * CORNER), k[0] + 1, Math.max(k[1], k[1] + k[3] * CORNER) + 1, c);
		}

		float battery = drone.battery();
		int barX = 14, barY = 14, barW = 90;
		graphics.fill(barX, barY, barX + barW, barY + 5, 0xA0000000);
		graphics.fill(barX, barY, barX + Math.round(barW * battery), barY + 5, battery < 0.2F ? McTheme.RED : McTheme.GREEN);
		graphics.text(minecraft.font, VanScreen.text("hud.battery", "Battery %s %%", Math.round(battery * 100)), barX, barY + 8, McTheme.WHITE, true);
		double range = s.drone().range();
		boolean edge = range > VanRules.DRONE_RANGE - 4.0;
		graphics.text(minecraft.font, VanScreen.text("hud.range", "Van %s / %s m", Math.round(range), (int) VanRules.DRONE_RANGE), barX, barY + 19,
			edge ? McTheme.RED : McTheme.GRAY, true);
		VanWire.Target aimed = VanClient.aimed();
		Component target = aimed == null ? VanScreen.text("hud.no_target", "No target") : VanScreen.name(aimed);
		graphics.centeredText(minecraft.font, target, w / 2, h / 2 + 12, aimed == null ? McTheme.GRAY : aimed.hostile() ? McTheme.RED : McTheme.AQUA);
		// the keys under the battery, wrapped: the bottom of the screen belongs to the hotbar, RAM and chat
		List<FormattedCharSequence> keys = minecraft.font.split(VanScreen.text("hud.keys",
			"WASD fly, Space up, Ctrl down, right-click: Hack Wheel (on nothing: the Van screen), Shift: leave the Van"), Math.min(w / 3, 180));
		for (int i = 0; i < keys.size(); i++) {
			graphics.text(minecraft.font, keys.get(i), barX, barY + 32 + i * 10, McTheme.GRAY, true);
		}
	}
}
