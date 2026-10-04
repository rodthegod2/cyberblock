package dev.cyberblock.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.net.ClientNet;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.hub.Afterlife;
import dev.cyberblock.hub.HubBlocks;
import dev.cyberblock.net.Payloads;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;

/**
 * The client half of the Afterlife hub (megaplan 7-H1): while this player is in the hub, Cyberpunk does not place Steve
 * ({@link HostState#released}: Minecraft is the whole picture there and V is parked). While the DLL shows the hub
 * ({@link HostState#hostInput}), its W A S D, Ctrl, Space and Shift ({@code key} forward, left, back, right, sneak, jump, sprint) walk
 * Steve and its mouse ({@code look {d:[dyaw,dpitch]}}, degrees) turns him (7D's "Input in the hub", megaplan 1.9). The pause screens
 * (Minecraft's and the Night City one) get an "Afterlife hub" button, "Leave the Afterlife" inside it, which sends
 * {@code /cyberblock hub} (the server decides); and the leaderboard boards draw their scores. The button shows only on a server with
 * the hub ({@code hs_ack.features} bit 16). Log lines: {@code hub: in the Afterlife (Cyberpunk does not drive Steve here)},
 * {@code hub: back from the Afterlife}, and which input is active there: {@code hub: input from Cyberpunk's keys and mouse (the DLL
 * shows the hub)} or {@code hub: input from Minecraft's own window (no linked host DLL)}.
 */
public final class HubClient implements ClientModInitializer {
	/** Entity.turn scales its arguments by this (mouse counts to degrees); the DLL's look deltas are degrees already. */
	private static final double TURN_SCALE = 0.15;

	/**
	 * The hub's movement keys as the DLL sends them, kept while a screen is open (vanilla releases every key when one opens) and given
	 * back to the game when it closes, like ClientInput.HeldKeys does for attack and use.
	 */
	static final class Keys {
		/** The DLL's names, in the order of {@link #mappings}. */
		static final List<String> NAMES = List.of("forward", "left", "back", "right", "sneak", "jump", "sprint");
		private final boolean[] down = new boolean[NAMES.size()];

		/** Stores the DLL's key; false for a name that is not a hub movement key (the caller handles it). */
		boolean host(final String k, final boolean isDown) {
			int i = NAMES.indexOf(k);
			if (i < 0) {
				return false;
			}

			this.down[i] = isDown;
			return true;
		}

		boolean down(final String k) {
			int i = NAMES.indexOf(k);
			return i >= 0 && this.down[i];
		}

		void apply(final KeyMapping[] to) {
			for (int i = 0; i < to.length; i++) {
				to[i].setDown(this.down[i]);
			}
		}

		void release(final KeyMapping[] to) {
			Arrays.fill(this.down, false);
			this.apply(to);
		}
	}

	private static final Keys keys = new Keys();
	private static boolean inHub, hostInput, hadScreen;

	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(HubBlocks.LEADERBOARD_ENTITY, LeaderboardRenderer::new);
		ClientTickEvents.END_CLIENT_TICK.register(HubClient::tick);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (paused(screen) && (ClientNet.features() & Payloads.FEATURE_HUB) != 0 && minecraft.player != null) {
				Screens.getWidgets(screen).add(Button.builder(label(inHub), b -> go(minecraft)).bounds(width - 102, 4, 98, 20).build());
			}
		});
	}

	/** Options' mappings for {@link Keys#NAMES}, in that order. */
	private static KeyMapping[] mappings(final Options o) {
		return new KeyMapping[] {o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyShift, o.keyJump, o.keySprint};
	}

	private static void tick(final Minecraft minecraft) {
		boolean now = minecraft.level != null && Afterlife.is(minecraft.level);
		if (now != inHub) {
			inHub = now;
			HostState.released = now;
			Cyberblock.LOG.info(now ? "hub: in the Afterlife (Cyberpunk does not drive Steve here)" : "hub: back from the Afterlife");
		}

		boolean input = HostState.hostInput();
		if (input != hostInput) {
			hostInput = input;
			if (!input) {
				keys.release(mappings(minecraft.options)); // nothing stays held once the DLL no longer speaks for this client
			}

			if (inHub) {
				Cyberblock.LOG.info(input ? "hub: input from Cyberpunk's keys and mouse (the DLL shows the hub)"
					: "hub: input from Minecraft's own window (no linked host DLL)");
			}
		}

		boolean screen = minecraft.gui.screen() != null;
		if (input && hadScreen && !screen) {
			keys.apply(mappings(minecraft.options)); // the screen released them; the DLL may still hold some
		}

		hadScreen = screen;
	}

	/**
	 * A DLL {@code key} (client thread): in the hub its movement keys hold Minecraft's own (vanilla then walks, jumps, sneaks and
	 * sprints Steve). True when it was one of them and is handled; false outside the hub or for any other key.
	 */
	public static boolean key(final Minecraft minecraft, final String k, final boolean down) {
		if (!HostState.hostInput() || !keys.host(k, down)) {
			return false;
		}

		if (minecraft.gui.screen() == null) {
			mappings(minecraft.options)[Keys.NAMES.indexOf(k)].setDown(down);
		}

		return true;
	}

	/**
	 * A DLL {@code look {d:[dyaw,dpitch]}} (client thread, Minecraft degrees): in the hub it turns Steve (pitch clamped by Entity.turn),
	 * never behind an open screen. True when handled here; false outside the hub (Flight's glide look takes it).
	 */
	public static boolean look(final Minecraft minecraft, final JsonObject m) {
		if (!HostState.hostInput()) {
			return false;
		}

		JsonArray d = m.getAsJsonArray("d");
		double dyaw = d.get(0).getAsDouble(), dpitch = d.get(1).getAsDouble();
		LocalPlayer player = minecraft.player;
		if (player != null && minecraft.gui.screen() == null && Math.abs(dyaw) <= 360.0 && Math.abs(dpitch) <= 360.0) {
			player.turn(dyaw / TURN_SCALE, dpitch / TURN_SCALE);
		}

		return true;
	}

	private static boolean paused(final Screen screen) {
		return screen instanceof PauseScreen pause && pause.showsPauseMenu() || screen instanceof NightCityPauseScreen;
	}

	static Component label(final boolean inside) {
		return inside ? Component.translatableWithFallback("cyberblock.hub.button.leave", "Leave the Afterlife")
			: Component.translatableWithFallback("cyberblock.hub.button.enter", "Afterlife hub");
	}

	private static void go(final Minecraft minecraft) {
		minecraft.gui.setScreen(null);
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand("cyberblock hub");
		}
	}
}
