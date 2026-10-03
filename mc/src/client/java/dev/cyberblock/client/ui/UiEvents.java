package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.CyberwareScreen;
import dev.cyberblock.client.cyber.SkillTreeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/** The UI messages that act on the game (screens, toasts, the waypoint), on the client thread. */
final class UiEvents {
	private UiEvents() {
	}

	/**
	 * {@code screen.open} (section 1.11 per-menu table): Cyberpunk intercepted one of its menus, Minecraft shows its own screen
	 * instead. A Minecraft screen the player already has open (chat, a container) is left alone.
	 */
	static void open(final String name) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) {
			return;
		}

		Screen current = minecraft.gui.screen();
		if (current != null) {
			Cyberblock.LOG.info("ui: screen {} not opened over {}", name, current.getClass().getSimpleName());
			return;
		}

		Cyberblock.LOG.info("ui: screen {}", name);
		if (name.equals("inventory") && minecraft.gameMode.isServerControlledInventory()) {
			player.sendOpenInventory();
			return;
		}

		Screen screen = screenFor(name, player);
		if (screen != null) {
			minecraft.gui.setScreen(screen);
		}
	}

	private static @Nullable Screen screenFor(final String name, final LocalPlayer player) {
		return switch (name) {
			case "inventory" -> new InventoryScreen(player);
			case "skills" -> new SkillTreeScreen();
			case "cyberware" -> new CyberwareScreen();
			default -> null;
		};
	}

	/** {@code notify}: a Minecraft toast. */
	static void notify(final UiModel.Notify notify) {
	}

	/** {@code call}: the call toast. */
	static void call(final UiModel.Call call) {
	}

	/** The UI data was dropped (new host session, Cyberblock off, link gone). */
	static void reset() {
		Waypoints.clear(Minecraft.getInstance());
	}
}
