package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.CyberwareScreen;
import dev.cyberblock.client.cyber.SkillTreeScreen;
import dev.cyberblock.client.ui.screen.AtlasScreen;
import dev.cyberblock.client.ui.screen.JournalScreen;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.client.ui.screen.PhoneScreen;
import dev.cyberblock.client.ui.screen.VendorScreen;
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
	 * instead. A Minecraft screen the player already has open (chat, a container) is left alone. Cyberpunk already swallowed the
	 * key, so a screen Minecraft cannot open (the DLL's view of Minecraft's screen was a moment old, or no world is loaded) opens
	 * Cyberpunk's own menu instead through the escape hatch (megaplan 9-U1): the key is never lost.
	 */
	static void open(final String name) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) {
			hatch(name, "no world");
			return;
		}

		Screen current = minecraft.gui.screen();
		if (current != null) {
			hatch(name, "over " + current.getClass().getSimpleName());
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
		} else {
			hatch(name, "no Minecraft screen");
		}
	}

	/** Cyberpunk's own menu for a screen Minecraft could not open ({@code uiact cpmenu}). */
	private static void hatch(final String name, final String why) {
		Cyberblock.LOG.info("ui: screen {} not opened ({}): Night City opens its own menu", name, why);
		UiLink.act("cpmenu", hatchFor(name), 0L, 0);
	}

	/** The {@code cpmenu} arg for a screen name: the hub menus Cyberpunk opens by name (Menus.reds), else its pause menu. */
	static String hatchFor(final String name) {
		return switch (name) {
			case "map", "journal", "inventory", "skills" -> name;
			default -> "pause";
		};
	}

	private static @Nullable Screen screenFor(final String name, final LocalPlayer player) {
		return switch (name) {
			case "pause" -> new NightCityPauseScreen();
			case "map" -> new AtlasScreen(null);
			case "journal" -> new JournalScreen(null);
			case "phone" -> new PhoneScreen(null);
			case "inventory" -> new InventoryScreen(player);
			case "skills" -> new SkillTreeScreen();
			case "cyberware" -> new CyberwareScreen();
			default -> null;
		};
	}

	/** {@code notify}: a Minecraft toast. */
	static void notify(final UiModel.Notify notify) {
		Cyberblock.LOG.debug("ui: notify {}", notify.kind());
		CyberToast.notify(Minecraft.getInstance().gui.toastManager(), notify);
	}

	/** {@code call}: the call toast. */
	static void call(final UiModel.Call call) {
		CyberToast.call(Minecraft.getInstance().gui.toastManager(), call);
	}

	/** The UI data was dropped (new host session, Cyberblock off, link gone). */
	static void reset() {
		Minecraft minecraft = Minecraft.getInstance();
		Waypoints.clear(minecraft);
		CyberToast.hideCall(minecraft.gui.toastManager());
		VendorScreen.closeIfOpen();
	}
}
