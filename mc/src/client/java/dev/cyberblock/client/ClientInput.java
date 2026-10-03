// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.HackWheel;
import dev.cyberblock.client.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.sdl.SDLVideo;

/** Host input, applied on the client thread: the host window has the focus, so Minecraft never sees these itself. */
final class ClientInput {
	/**
	 * What the host asked for with "hud" (a re-test 3 host always says false). A vehicle no longer hides the HUD (plan retest3 M2,
	 * F3): hiding it would also hide the first-person hand (26.3 skips renderItemInHand while the HUD is hidden); the UI track trims
	 * the vehicle HUD itself.
	 */
	private static boolean hudHidden;

	private ClientInput() {
	}

	/** The view the player chose with F5 (plan retest3 M4): false first person, true third person behind V. Any thread reads it. */
	private static volatile boolean thirdPerson;

	/** {"t":"persp","mode":"first"|"back"} (plan retest3 section 1.1). */
	static String perspMessage(final boolean back) {
		return "{\"t\":\"persp\",\"mode\":\"" + (back ? "back" : "first") + "\"}";
	}

	/** The chosen view for the host: on F5 and after every host hello (the DLL starts each session in first person). */
	static void sendPerspective() {
		boolean back = thirdPerson;
		Cyberblock.events.accept(perspMessage(back));
		Cyberblock.LOG.info("persp: {}", back ? "back" : "first");
	}

	/** F5: first person and third person (behind V) by turns; the action bar says which. Client thread. */
	private static void togglePerspective(final Minecraft minecraft) {
		thirdPerson = !thirdPerson;
		sendPerspective();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(thirdPerson ? Component.translatableWithFallback("cyberblock.view.third", "Third person")
				: Component.translatableWithFallback("cyberblock.view.first", "First person"));
		}
	}

	/** The host went away: Steve, his hand and the HUD are back. */
	static void reset(final Minecraft minecraft) {
		hudHidden = false;
		HostState.setVehicle(false);
		HostState.resetKeys();
		applyHud(minecraft);
	}

	private static void applyHud(final Minecraft minecraft) {
		if (minecraft.gui.hud.isHidden() != hudHidden) {
			minecraft.gui.hud.toggle();
		}
	}

	/**
	 * Presses a key bound to the open screen, the way vanilla's KeyboardHandler does (the screen decides: the inventory key closes a
	 * container screen, drop drops the hovered stack, Ctrl+drop the whole stack, number keys swap it into the hotbar; other screens
	 * ignore it). A mapping bound to the mouse (use, attack and pick by default) is not a key press: the host sends "click" while a
	 * screen is open.
	 */
	private static void pressOnScreen(final Screen screen, final KeyMapping mapping, final int mods) {
		InputConstants.Key bound = ((KeyMappingAccessor)mapping).cyberblock$getKey();
		if (bound.getType() != InputConstants.Type.KEYBOARD || bound.equals(InputConstants.UNKNOWN)) {
			return;
		}

		keyPressed(screen, bound.getValue(), mods);
	}

	/**
	 * A key press for the open screen as KeyboardHandler gives it: afterKeyboardAction, then keyPressed. KeyEvent is (scancode,
	 * keycode, modifiers) as SDL reports them; passing the scancode as the keycode too would make the default swap key (scancode 9)
	 * look like Tab (keycode 9) to the screen's focus navigation.
	 */
	private static void keyPressed(final Screen screen, final int scancode, final int mods) {
		screen.afterKeyboardAction();
		screen.keyPressed(new KeyEvent(scancode, SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short)0, false), mods));
	}

	/** The message's "mods": SDL modifier bits as Minecraft 26.3 reads them (InputConstants.MOD_SHIFT 3, MOD_CONTROL 192, MOD_ALT 768). */
	private static int mods(final JsonObject m) {
		return m.has("mods") ? m.get("mods").getAsInt() : 0;
	}

	static void handle(final Minecraft minecraft, final JsonObject m) {
		LocalPlayer player = minecraft.player;
		switch (m.get("t").getAsString()) {
			case "key" -> {
				String k = m.get("k").getAsString();
				boolean down = !m.has("down") || m.get("down").getAsBoolean();
				if (k.equals("escape")) {
					// the screen's own Esc handling: it closes only when shouldCloseOnEsc (the death screen stays), chat drops its draft
					Screen screen = minecraft.gui.screen();
					if (down && screen != null) {
						keyPressed(screen, InputConstants.KEY_ESCAPE, 0);
					}

					return;
				}

				// Space and Left Shift: Cyberpunk keeps them (V jumps and sprints) and the host moves Steve, so vanilla jump and sprint
				// are never pressed; they are stored for the glide start and the cyber track (which hears "key" through the Bridge)
				if (k.equals("jump") || k.equals("sprint")) {
					HostState.setKey(k, down);
					return;
				}

				// F5 ("perspective", plan retest3 M4): Cyberpunk moves its camera behind V (persp) and says so with cam.fp, which
				// PlayerSync follows; Minecraft's own view toggle would only fight it. Other keys ("hack", "skills") belong to the cyber
				// track's listener.
				if (k.equals("perspective")) {
					if (down) {
						togglePerspective(minecraft);
					}

					return;
				}

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
						pressOnScreen(screen, key, mods(m));
					}
				} else {
					// right-click while the hack key is held fires the chip the wheel picked (HackWheel), never a use
					if (key == minecraft.options.keyUse && HackWheel.use(minecraft, down)) {
						return;
					}

					// Ctrl+Q drops the whole stack: vanilla asks the window whether Ctrl is down, and the host's window has the keyboard
					if (key == minecraft.options.keyDrop && down && (mods(m) & InputConstants.MOD_CONTROL) != 0) {
						if (player != null && minecraft.gameMode != null && !player.isSpectator()) {
							minecraft.gameMode.dropItem(player, true);
						}

						return;
					}

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
					pressOnScreen(screen, minecraft.options.keyHotbarSlots[n], mods(m));
				} else if (player != null) {
					player.getInventory().setSelectedSlot(n);
				}
			}
			case "look" -> Flight.look(m);
			case "cursor" -> HostCursor.move(minecraft, m.get("x").getAsDouble(), m.get("y").getAsDouble());
			case "click" -> HostCursor.click(minecraft, m.get("b").getAsInt(), !m.has("down") || m.get("down").getAsBoolean(), mods(m));
			case "char" -> {
				// {"t":"char","c":codepoint}: text typed while a screen is open (chat, sign, anvil...), as SDL's text input gives it
				int c = m.get("c").getAsInt();
				Screen screen = minecraft.gui.screen();
				if (screen != null && Character.isValidCodePoint(c)) {
					screen.charTyped(new CharacterEvent(c));
				}
			}
			case "skey" -> {
				// {"t":"skey","sc":SDL scancode,"mods":bits}: a named key for the open screen (Enter, Backspace, arrows, Tab, Home...)
				int scancode = m.get("sc").getAsInt();
				Screen screen = minecraft.gui.screen();
				if (screen != null && scancode > 0 && scancode < SDLScancode.SDL_SCANCODE_COUNT) {
					keyPressed(screen, scancode, mods(m));
				}
			}
			case "scroll" -> {
				int notches = m.get("d").getAsInt();
				if (minecraft.gui.screen() != null) {
					HostCursor.scroll(minecraft, notches);
				} else if (player != null && !HackWheel.scroll(minecraft, notches)) {
					// the hotbar, unless the hack key is held and the wheel picked a quickhack chip instead
					Inventory inventory = player.getInventory();
					int size = Inventory.getSelectionSize();
					inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() - notches, size));
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
