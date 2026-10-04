package dev.cyberblock.client.mode.downed;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.ui.DownedHud;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.mode.downed.Downed;
import dev.cyberblock.mode.downed.DownedWire;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The client side of Downed (megaplan 8-T2): the downlist makes every downed player crawl here too ({@link Downed#clientDowned}, read by
 * the pose mixin); this player's own status opens the {@link DownedScreen} when it goes down and closes it when it is back up; the
 * HUD shows the countdowns and revive bars; the pause screen gets a "Downed..." button while down. Client thread.
 */
public final class DownedClient {
	private static volatile Set<UUID> down = Set.of();
	private static int bad;

	private DownedClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		Downed.clientDowned = player -> down.contains(player.getUUID());
		UiRoutes.listen(DownedWire.T_LIST, DownedClient::onList);
		UiRoutes.listen(DownedWire.T_DOWNED, DownedClient::onStatus);
		UiRoutes.listen(DownedWire.T_REVIVE, DownedClient::onRevive);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, DownedHud.ID, DownedHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, DownedHud.REVIVE_ID, DownedHud::drawRevive);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			down = Set.of();
			DownedHud.status = DownedWire.Status.UP;
			DownedHud.revive = null;
		});
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (DownedHud.status.down() && paused(screen)) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatableWithFallback("cyberblock.downed.button", "Downed..."),
					b -> minecraft.gui.setScreen(new DownedScreen())).bounds(width - 102, 28, 98, 20).build());
			}
		});
	}

	private static boolean paused(final Screen screen) {
		return screen instanceof PauseScreen pause && pause.showsPauseMenu() || screen instanceof NightCityPauseScreen;
	}

	static void onList(final String json) {
		Set<UUID> list = DownedWire.parseList(json);
		if (list == null) {
			dropped();
			return;
		}

		down = Set.copyOf(list);
	}

	static void onStatus(final String json) {
		DownedWire.Status s = DownedWire.parseStatus(json);
		if (s == null) {
			dropped();
			return;
		}

		boolean was = DownedHud.status.down();
		DownedHud.status = s;
		DownedHud.statusAt = System.currentTimeMillis();
		Minecraft minecraft = Minecraft.getInstance();
		if (s.down() && !was) {
			minecraft.gui.setScreen(new DownedScreen());
		} else if (!s.down() && minecraft.gui.screen() instanceof DownedScreen) {
			minecraft.gui.setScreen(null);
		} else if (minecraft.gui.screen() instanceof DownedScreen screen) {
			screen.update();
		}
	}

	static void onRevive(final String json) {
		DownedWire.Revive r = DownedWire.parseRevive(json);
		if (r == null) {
			dropped();
			return;
		}

		DownedHud.revive = r;
		DownedHud.reviveAt = System.currentTimeMillis();
	}

	private static void dropped() {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("downed: bad data dropped ({} so far)", bad);
		}
	}
}
