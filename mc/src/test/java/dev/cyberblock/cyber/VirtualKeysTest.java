package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cyberblock.client.cyber.VirtualKeys;
import org.junit.jupiter.api.Test;

class VirtualKeysTest {
	// SDL scancodes, as InputConstants.KEY_* in 26.3
	private static final int SC_A = 4, SC_G = 10, SC_K = 14, SC_Z = 29, SC_1 = 30, SC_9 = 38, SC_0 = 39;
	private static final int SC_F1 = 58, SC_F12 = 69, SC_ENTER = 40, SC_SPACE = 44, SC_F13 = 104, SC_LSHIFT = 225;

	@Test
	void letters() {
		assertEquals(0x41, VirtualKeys.of(true, SC_A));
		assertEquals(0x47, VirtualKeys.of(true, SC_G));
		assertEquals(0x4B, VirtualKeys.of(true, SC_K));
		assertEquals(0x5A, VirtualKeys.of(true, SC_Z));
	}

	@Test
	void digits() {
		assertEquals(0x31, VirtualKeys.of(true, SC_1));
		assertEquals(0x39, VirtualKeys.of(true, SC_9));
		assertEquals(0x30, VirtualKeys.of(true, SC_0));
	}

	@Test
	void functionKeys() {
		assertEquals(0x70, VirtualKeys.of(true, SC_F1));
		assertEquals(0x7B, VirtualKeys.of(true, SC_F12));
	}

	@Test
	void everythingElseFallsBackToTheDefault() {
		for (int sc : new int[]{SC_ENTER, SC_SPACE, SC_F13, SC_LSHIFT, 0, -1, 3}) {
			assertEquals(-1, VirtualKeys.of(true, sc), "scancode " + sc);
			assertEquals(VirtualKeys.VK_G, VirtualKeys.orDefault(true, sc, VirtualKeys.VK_G));
		}

		// a mouse button bound to the mapping: Cyberpunk cannot forward it either
		assertEquals(-1, VirtualKeys.of(false, SC_A));
		assertEquals(VirtualKeys.VK_K, VirtualKeys.orDefault(false, 1, VirtualKeys.VK_K));
		assertEquals(0x48, VirtualKeys.orDefault(true, SC_G + 1, VirtualKeys.VK_K));
	}

	@Test
	void bindsJson() {
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":71,\"skills\":75}}", CyberWire.binds(VirtualKeys.VK_G, VirtualKeys.VK_K));
	}
}
