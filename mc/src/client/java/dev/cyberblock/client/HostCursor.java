package dev.cyberblock.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The host's mouse, for Minecraft screens (inventory, chat, ...): the OS cursor is over the host's window, not Minecraft's,
 * so the host sends where it is (0..1 of the window) and which buttons go down or up. While a screen is open and a host is
 * attached, this is the cursor Minecraft uses (hover, tooltips, clicks) and it is drawn into the exported overlay.
 */
public final class HostCursor {
	/** Cursor in GUI-scaled coordinates, NaN until the host has sent one. */
	private static double x = Double.NaN;
	private static double y = Double.NaN;
	private static @Nullable MouseButtonInfo held;
	private static long lastClickMillis;
	private static int lastClickButton = -1;
	private static @Nullable Screen lastClickScreen;

	private HostCursor() {
	}

	/** True when Minecraft should use the host's cursor instead of the OS one (the host drives, or the hub is shown by the DLL). */
	public static boolean active() {
		return !Double.isNaN(x) && Minecraft.getInstance().gui.screen() != null && (HostState.live() != null || HostState.hostInput());
	}

	public static double x() {
		return x;
	}

	public static double y() {
		return y;
	}

	/** {"t":"cursor","x":0..1,"y":0..1}, on the client thread. */
	static void move(final Minecraft minecraft, final double fx, final double fy) {
		Window window = minecraft.getWindow();
		double nx = Math.clamp(fx, 0.0, 1.0) * window.getGuiScaledWidth();
		double ny = Math.clamp(fy, 0.0, 1.0) * window.getGuiScaledHeight();
		double dx = Double.isNaN(x) ? 0.0 : nx - x;
		double dy = Double.isNaN(y) ? 0.0 : ny - y;
		x = nx;
		y = ny;
		Screen screen = minecraft.gui.screen();
		if (screen != null) {
			screen.mouseMoved(x, y);
			if (held != null) {
				screen.mouseDragged(new MouseButtonEvent(x, y, held), dx, dy);
			}

			screen.afterMouseMove();
		}
	}

	/**
	 * {"t":"click","b":0 left | 1 right | 2 middle,"down":bool,"mods":bits}, on the client thread. Minecraft 26.3 numbers its mouse
	 * buttons like SDL (1 left, 2 middle, 3 right), so the protocol's numbers are translated. The modifiers (SDL bits, 0 when
	 * absent) are the Shift and Ctrl held with the click: Shift-click quick-moves a stack.
	 */
	static void click(final Minecraft minecraft, final int protocolButton, final boolean down, final int mods) {
		int button = switch (protocolButton) {
			case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
			case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
			case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
			default -> -1;
		};
		Screen screen = minecraft.gui.screen();
		if (button < 0 || screen == null || Double.isNaN(x)) {
			held = null;
			return;
		}

		MouseButtonInfo info = new MouseButtonInfo(button, mods);
		MouseButtonEvent event = new MouseButtonEvent(x, y, info);
		if (down) {
			held = info;
			screen.afterMouseAction();
			long now = Util.getMillis();
			boolean doubleClick = now - lastClickMillis < 250L && lastClickScreen == screen && lastClickButton == button;
			if (screen.mouseClicked(event, doubleClick)) {
				lastClickMillis = now;
				lastClickButton = button;
				lastClickScreen = screen;
			}
		} else {
			held = null;
			screen.mouseReleased(event);
		}
	}

	/** {"t":"scroll","d":+-1} while a screen is open: scrolls the screen under the cursor. */
	static void scroll(final Minecraft minecraft, final int notches) {
		Screen screen = minecraft.gui.screen();
		if (screen != null && !Double.isNaN(x)) {
			screen.mouseScrolled(x, y, 0.0, notches);
			screen.afterMouseAction();
		}
	}

	/** After a screen is drawn: a small white arrow with a black edge at the host's cursor, so it shows in the exported overlay. */
	public static void draw(final GuiGraphicsExtractor graphics) {
		if (!active()) {
			return;
		}

		int cx = (int) x;
		int cy = (int) y;
		for (int r = 0; r < 9; r++) {
			graphics.fill(cx - 1, cy + r - 1, cx + r + 2, cy + r + 1, 0xFF000000);
		}

		for (int r = 0; r < 8; r++) {
			graphics.fill(cx, cy + r, cx + r + 1, cy + r + 1, 0xFFFFFFFF);
		}
	}
}
