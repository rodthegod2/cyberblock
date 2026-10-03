// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.client.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLVideo;

/** Host input, applied on the client thread: the host window has the focus, so Minecraft never sees these itself. */
final class ClientInput {
	/** What the host asked for with "hud"; in a vehicle the HUD is hidden whatever it said. */
	private static boolean hudHidden;

	private ClientInput() {
	}

	/** The host went away: Steve, his hand and the HUD are back. */
	static void reset(final Minecraft minecraft) {
		hudHidden = false;
		HostState.setVehicle(false);
		HostState.resetKeys();
		applyHud(minecraft);
	}

	private static void applyHud(final Minecraft minecraft) {
		if (minecraft.gui.hud.isHidden() != (hudHidden || HostState.vehicle())) {
			minecraft.gui.hud.toggle();
		}
	}

	/**
	 * Presses a key bound to the open screen, the way vanilla's KeyboardHandler does (the screen decides: the inventory key closes a
	 * container screen, drop drops the hovered stack, number keys swap it into the hotbar; other screens ignore it). A mapping bound
	 * to the mouse (use, attack and pick by default) is not a key press: the host sends "click" while a screen is open.
	 */
	private static void pressOnScreen(final Screen screen, final KeyMapping mapping) {
		InputConstants.Key bound = ((KeyMappingAccessor)mapping).cyberblock$getKey();
		if (bound.getType() != InputConstants.Type.KEYBOARD || bound.equals(InputConstants.UNKNOWN)) {
			return;
		}

		// KeyEvent is (scancode, keycode, modifiers) as SDL reports them: the mapping holds the scancode. Passing it as the keycode too
		// would make the default swap key (scancode 9) look like Tab (keycode 9) to the screen's focus navigation.
		int scancode = bound.getValue();
		screen.afterKeyboardAction();
		screen.keyPressed(new KeyEvent(scancode, SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short)0, false), 0));
	}

	static void handle(final Minecraft minecraft, final JsonObject m) {
		LocalPlayer player = minecraft.player;
		switch (m.get("t").getAsString()) {
			case "key" -> {
				String k = m.get("k").getAsString();
				boolean down = !m.has("down") || m.get("down").getAsBoolean();
				if (k.equals("escape")) {
					if (down && minecraft.gui.screen() != null) {
						minecraft.gui.screen().onClose();
					}

					return;
				}

				// Space and Left Shift: Cyberpunk keeps them (V jumps and sprints) and the host moves Steve, so vanilla jump and sprint
				// are never pressed; they are stored for the glide start and the cyber track (which hears "key" through the Bridge)
				if (k.equals("jump") || k.equals("sprint")) {
					HostState.setKey(k, down);
					return;
				}

				// F5 ("perspective"): the host drives the camera, Minecraft's own view toggle would only fight it. Other keys ("hack",
				// "skills") belong to the cyber track's listener.
				KeyMapping key = switch (k) {
					case "use" -> minecraft.options.keyUse;
					case "attack" -> minecraft.options.keyAttack;
					case "pick" -> minecraft.options.keyPickItem;
					case "inventory" -> minecraft.options.keyInventory;
					case "drop" -> minecraft.options.keyDrop;
					case "swap" -> minecraft.options.keySwapOffhand;
					case "chat" -> minecraft.options.keyChat;
					case "command" -> minecraft.options.keyCommand;
					default -> null;
				};
				if (key == null) {
					return;
				}

				Screen screen = minecraft.gui.screen();
				if (screen != null) {
					// vanilla gives a key to the open screen and never to the game (handleKeybinds does not run, and KeyMapping.releaseAll
					// only runs when a screen opens): a click counted now would stay queued and fire as soon as the screen closes
					if (down) {
						pressOnScreen(screen, key);
					}
				} else {
					if (down && !key.isDown()) {
						KeyMappingAccessor access = (KeyMappingAccessor)key;
						access.cyberblock$setClickCount(access.cyberblock$getClickCount() + 1);
					}

					key.setDown(down);
				}
			}
			case "slot" -> {
				int n = Math.clamp(m.get("n").getAsInt(), 0, Inventory.getSelectionSize() - 1);
				Screen screen = minecraft.gui.screen();
				if (screen != null) {
					// in an inventory the number keys swap the hovered stack with that hotbar slot
					pressOnScreen(screen, minecraft.options.keyHotbarSlots[n]);
				} else if (player != null) {
					player.getInventory().setSelectedSlot(n);
				}
			}
			case "cursor" -> HostCursor.move(minecraft, m.get("x").getAsDouble(), m.get("y").getAsDouble());
			case "click" -> HostCursor.click(minecraft, m.get("b").getAsInt(), !m.has("down") || m.get("down").getAsBoolean());
			case "scroll" -> {
				if (minecraft.gui.screen() != null) {
					HostCursor.scroll(minecraft, m.get("d").getAsInt());
				} else if (player != null) {
					Inventory inventory = player.getInventory();
					int size = Inventory.getSelectionSize();
					inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() - m.get("d").getAsInt(), size));
				}
			}
			case "hud" -> {
				hudHidden = m.get("hidden").getAsBoolean();
				applyHud(minecraft);
			}
			case "state" -> {
				boolean active = !m.has("active") || m.get("active").getAsBoolean();
				HostState.setVehicle(active && m.has("vehicle") && m.get("vehicle").getAsBoolean());
				applyHud(minecraft);
			}
			case "view" -> {
				// match the host's picture exactly: un-minimize/un-maximize first (resizing a maximized window is ignored)
				int w = m.get("w").getAsInt(), h = m.get("h").getAsInt();
				long handle = minecraft.getWindow().handle();
				SDLVideo.SDL_RestoreWindow(handle);
				minecraft.getWindow().setWindowed(w, h);
				SDLVideo.SDL_SetWindowSize(handle, w, h);
				SDLVideo.SDL_SyncWindow(handle);
			}
			default -> {
			}
		}
	}
}
