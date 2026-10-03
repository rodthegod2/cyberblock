package dev.cyberblock.client.cyber;

/**
 * Minecraft key (26.3: SDL scancodes) to Windows virtual-key code, for the `binds` message. Cyberpunk only forwards
 * letters, digits and F1-F12, so every other key (and any mouse button) stands for the mapping's default.
 */
public final class VirtualKeys {
	public static final int VK_G = 0x47;
	public static final int VK_K = 0x4B;
	// SDL scancodes (InputConstants.KEY_*): A..Z = 4..29, 1..9 = 30..38, 0 = 39, F1..F12 = 58..69
	private static final int SC_A = 4;
	private static final int SC_Z = 29;
	private static final int SC_1 = 30;
	private static final int SC_9 = 38;
	private static final int SC_0 = 39;
	private static final int SC_F1 = 58;
	private static final int SC_F12 = 69;

	private VirtualKeys() {
	}

	/** The virtual-key code of a keyboard scancode, or -1 when Cyberpunk would not forward it. */
	public static int of(final boolean keyboard, final int scancode) {
		if (!keyboard) {
			return -1;
		}

		if (scancode >= SC_A && scancode <= SC_Z) {
			return 0x41 + scancode - SC_A;
		}

		if (scancode >= SC_1 && scancode <= SC_9) {
			return 0x31 + scancode - SC_1;
		}

		if (scancode == SC_0) {
			return 0x30;
		}

		if (scancode >= SC_F1 && scancode <= SC_F12) {
			return 0x70 + scancode - SC_F1;
		}

		return -1;
	}

	/** As of, with the default for a key Cyberpunk cannot forward. */
	public static int orDefault(final boolean keyboard, final int scancode, final int fallback) {
		int vk = of(keyboard, scancode);
		return vk < 0 ? fallback : vk;
	}
}
