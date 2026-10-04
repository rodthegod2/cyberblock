package dev.cyberblock.client.mode.match;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.CyberwareScreen;
import dev.cyberblock.client.net.Roster;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.mode.match.MatchWire;
import dev.cyberblock.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The client half of matches and PvP (megaplan 8-M1): the Match Terminal's screen ({@code cyberblock:ui t:"match"} with
 * {@code open:true} opens {@link MatchScreen}), the match HUD (clock, scores, capture points) and the PvP button: on the HUD (the
 * PvP chip; it becomes a button whenever the mouse is free: chat or inventory open) and on the Cyberware screen. The buttons send
 * {@code /cyberblock pvp on|off}; the server decides (the 15 s lock). Log line: {@code match: screen opened (<n> matches, create
 * allowed|refused: <why>)}.
 */
public final class MatchClient implements ClientModInitializer {
	static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "match_hud");
	private static volatile MatchWire.@Nullable State state;

	@Override
	public void onInitializeClient() {
		UiRoutes.listen(MatchWire.T, MatchClient::onState);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, MatchHud::draw);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = null);
		MatchShots.init();
		ClientTickEvents.END_CLIENT_TICK.register(MatchShots::tick);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (minecraft.player == null) {
				return;
			}

			if (screen instanceof CyberwareScreen) {
				// top right: the Cyberware panel starts 24 px lower at the smallest GUI and draws over widgets inside it
				Screens.getWidgets(screen).add(pvpButton(width - 104, 4, 100));
			} else if (screen instanceof ChatScreen || screen instanceof InventoryScreen) {
				int[] chip = MatchHud.chip(width, height);
				Screens.getWidgets(screen).add(pvpButton(chip[0], chip[1], chip[2]));
			}
		});
	}

	/** The newest state from the server, or null before the first. */
	static MatchWire.@Nullable State state() {
		return state;
	}

	private static void onState(final String json) {
		MatchWire.State s = MatchWire.parse(json);
		state = s;
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.gui.screen();
		if (s.open()) {
			Cyberblock.LOG.info("match: screen opened ({} matches, create {})", s.matches().size(), s.canCreate() ? "allowed" : "refused: " + s.why());
			minecraft.gui.setScreen(new MatchScreen());
		} else if (screen instanceof MatchScreen m) {
			m.refresh();
		}
	}

	/** This player's PvP toggle: the server's newest word (the roster, or the match state when newer). */
	static boolean pvpOn() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}

		Roster.Entry me = Roster.of(minecraft.player.getUUID());
		return me != null ? me.pvp() : state != null && state.pvpOn();
	}

	/** A request to the server's matches; false when the server has no Cyberblock channel. */
	static boolean act(final String json) {
		if (!ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			return false;
		}

		ClientPlayNetworking.send(new Payloads.Act(MatchWire.T, json));
		return true;
	}

	static void sendPvp(final boolean on) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand("cyberblock pvp " + (on ? "on" : "off"));
		}
	}

	static Component pvpLabel(final boolean on) {
		return on ? Component.translatableWithFallback("cyberblock.pvp.button.on", "PvP: ON").withColor(0xFF5555)
			: Component.translatableWithFallback("cyberblock.pvp.button.off", "PvP: off");
	}

	/** The PvP button: shows the toggle and asks for the other value; the label follows the server's answer. */
	static Button pvpButton(final int x, final int y, final int w) {
		Button b = new FollowingButton(x, y, w);
		b.setTooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.pvp.button.tip",
			"PvP: a player can hurt you only when you both have it on. After a fight it stays on 15 s")));
		return b;
	}

	/** A button whose label follows the toggle while it is shown (the server may refuse "off" during the 15 s lock). */
	private static final class FollowingButton extends Button.Plain {
		FollowingButton(final int x, final int y, final int w) {
			super(x, y, w, MatchHud.CHIP_H, pvpLabel(pvpOn()), b -> sendPvp(!pvpOn()), DEFAULT_NARRATION);
		}

		@Override
		protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
			this.setMessage(pvpLabel(pvpOn()));
			super.extractContents(graphics, mouseX, mouseY, a);
		}
	}
}
