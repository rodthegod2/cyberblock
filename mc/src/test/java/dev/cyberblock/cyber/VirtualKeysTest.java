package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.cyber.VirtualKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VirtualKeysTest {
	// SDL scancodes, as InputConstants.KEY_* in 26.3
	private static final int SC_A = 4, SC_B = 5, SC_G = 10, SC_K = 14, SC_W = 26, SC_Z = 29, SC_1 = 30, SC_9 = 38, SC_0 = 39;
	private static final int SC_F1 = 58, SC_F5 = 62, SC_F9 = 66, SC_F12 = 69, SC_ENTER = 40, SC_SPACE = 44, SC_F13 = 104, SC_LSHIFT = 225;

	@Test
	void letters() {
		assertEquals(0x42, VirtualKeys.of(true, SC_B));
		assertEquals(0x47, VirtualKeys.of(true, SC_G));
		assertEquals(0x4B, VirtualKeys.of(true, SC_K));
		assertEquals(0x5A, VirtualKeys.of(true, SC_Z));
	}

	@Test
	void digits() {
		assertEquals(0x30, VirtualKeys.of(true, SC_0));
	}

	@Test
	void functionKeys() {
		assertEquals(0x70, VirtualKeys.of(true, SC_F1));
		assertEquals(0x7B, VirtualKeys.of(true, SC_F12));
	}

	@Test
	void keysTheDllRefusesStandForTheDefault() {
		for (int sc : new int[]{SC_A, SC_W, SC_1, SC_9, SC_F5, SC_F9}) {
			assertEquals(-1, VirtualKeys.of(true, sc), "scancode " + sc);
			assertEquals(VirtualKeys.VK_G, VirtualKeys.orDefault(true, sc, VirtualKeys.VK_G));
		}
	}

	@Test
	void everythingElseFallsBackToTheDefault() {
		for (int sc : new int[]{SC_ENTER, SC_SPACE, SC_F13, SC_LSHIFT, 0, -1, 3}) {
			assertEquals(-1, VirtualKeys.of(true, sc), "scancode " + sc);
			assertEquals(VirtualKeys.VK_G, VirtualKeys.orDefault(true, sc, VirtualKeys.VK_G));
		}

		// a mouse button bound to the mapping: Cyberpunk cannot forward it either
		assertEquals(-1, VirtualKeys.of(false, SC_B));
		assertEquals(VirtualKeys.VK_K, VirtualKeys.orDefault(false, 1, VirtualKeys.VK_K));
		assertEquals(0x48, VirtualKeys.orDefault(true, SC_G + 1, VirtualKeys.VK_K));
	}

	/** The SDL scancode of a letter, digit or F1-F12 virtual key. */
	private static int scancode(final int vk) {
		if (vk >= 0x41 && vk <= 0x5A) {
			return SC_A + vk - 0x41;
		}

		if (vk == 0x30) {
			return SC_0;
		}

		if (vk >= 0x31 && vk <= 0x39) {
			return SC_1 + vk - 0x31;
		}

		return SC_F1 + vk - 0x70;
	}

	/** shared/vectors.json `binds`, which the DLL's keys::bindable is checked against too: the two lists agree. */
	@Test
	void forwardableKeysMatchTheSharedBindsVectors() throws IOException {
		JsonObject vectors = JsonParser.parseString(Files.readString(Path.of("..", "shared", "vectors.json"))).getAsJsonObject();
		int cases = 0;
		for (JsonElement c : vectors.getAsJsonArray("binds")) {
			int vk = c.getAsJsonObject().get("vk").getAsInt();
			boolean bindable = c.getAsJsonObject().get("bindable").getAsBoolean();
			assertEquals(bindable ? vk : -1, VirtualKeys.of(true, scancode(vk)), "virtual key " + vk);
			cases++;
		}

		assertEquals(48, cases);
	}

	@Test
	void bindsJson() {
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":71,\"skills\":75}}", CyberWire.binds(VirtualKeys.VK_G, VirtualKeys.VK_K));
	}
}
