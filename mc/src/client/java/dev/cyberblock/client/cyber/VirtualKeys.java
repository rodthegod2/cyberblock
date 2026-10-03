package dev.cyberblock.client.cyber;

import java.util.Arrays;
import java.util.Set;

/**
 * Minecraft key (26.3: SDL scancodes) to Windows virtual-key code, for the `binds` message. Cyberpunk only forwards
 * letters, digits and F1-F12, and Cyberblock.dll refuses the ones that already do something while Cyberblock is active
 * (keys::bindable): movement and crouch (W A S D C), the keys forwarded to Minecraft (E Q T X 1-9 F5), Cyberpunk's own
 * keys that pass through (F I M J H N B P V Z R) and F9. Every other key (and any mouse button) stands for the
 * mapping's default. The shared/vectors.json `binds` set keeps this list and the DLL's the same.
 */
public final class VirtualKeys {
	public static final int VK_G = 0x47;
	public static final int VK_K = 0x4B;
	public static final int VK_U = 0x55;
	public static final int VK_Y = 0x59;
	/** The defaults of hack, skill tree, cyberware screen and activate implant, in the `binds` order (plan retest2 section 1.8). */
	public static final int[] DEFAULTS = {VK_G, VK_K, VK_U, VK_Y};
	// SDL scancodes (InputConstants.KEY_*): A..Z = 4..29, 1..9 = 30..38, 0 = 39, F1..F12 = 58..69
	private static final int SC_A = 4;
	private static final int SC_Z = 29;
	private static final int SC_1 = 30;
	private static final int SC_9 = 38;
	private static final int SC_0 = 39;
	private static final int SC_F1 = 58;
	private static final int SC_F12 = 69;
	private static final int VK_F5 = 0x74;
	private static final int VK_F9 = 0x78;
	/** Virtual keys the DLL refuses as a bind: it would keep the previous one, while Minecraft showed the new key. */
	private static final Set<Integer> REFUSED = Set.of(
		(int) 'W', (int) 'A', (int) 'S', (int) 'D', (int) 'C', (int) 'E', (int) 'Q', (int) 'T', (int) 'X',
		(int) 'F', (int) 'I', (int) 'M', (int) 'J', (int) 'H', (int) 'N', (int) 'B', (int) 'P', (int) 'V', (int) 'Z', (int) 'R',
		(int) '1', (int) '2', (int) '3', (int) '4', (int) '5', (int) '6', (int) '7', (int) '8', (int) '9', VK_F5, VK_F9);

	private VirtualKeys() {
	}

	/** The virtual-key code of a keyboard scancode, or -1 when Cyberpunk would not forward it as this binding. */
	public static int of(final boolean keyboard, final int scancode) {
		int vk = keyboard ? named(scancode) : -1;
		return REFUSED.contains(vk) ? -1 : vk;
	}

	/** The virtual-key code of a letter, digit or F1-F12 scancode, else -1. */
	private static int named(final int scancode) {
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

	/** Each key as `of` gave it, a refused one (-1) as its default (DEFAULTS, by position). */
	private static int[] resolved(final int[] keys) {
		int[] out = new int[keys.length];
		for (int i = 0; i < keys.length; i++) {
			out[i] = keys[i] < 0 ? DEFAULTS[i] : keys[i];
		}

		return out;
	}

	/**
	 * Whether any two of the keys (hack, skills, cyberware, activate, each as `of` gives it, -1: Cyberpunk cannot forward it)
	 * would be the same key in Cyberpunk once a refused one stands for its default. The DLL's keys::apply_binds applies the
	 * same rule.
	 */
	public static boolean clash(final int... keys) {
		int[] r = resolved(keys);
		for (int i = 0; i < r.length; i++) {
			for (int j = i + 1; j < r.length; j++) {
				if (r[i] == r[j]) {
					return true;
				}
			}
		}

		return false;
	}

	/** The keys `binds` sends: each key, a refused one as its default, and all the defaults when any two clash. */
	public static int[] binds(final int... keys) {
		return clash(keys) ? Arrays.copyOf(DEFAULTS, keys.length) : resolved(keys);
	}

	/** How a forwardable virtual key is written on the keyboard: "G", "0", "F10". */
	public static String name(final int vk) {
		return vk >= 0x70 && vk <= 0x7B ? "F" + (vk - 0x6F) : String.valueOf((char) vk);
	}
}
