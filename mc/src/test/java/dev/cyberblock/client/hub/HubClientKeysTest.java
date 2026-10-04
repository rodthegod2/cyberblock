package dev.cyberblock.client.hub;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.HostState;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 7D's "Input in the hub" (megaplan 1.9): the DLL's W A S D, Ctrl, Space and Shift walk Steve while the hub is shown. */
class HubClientKeysTest {
	@AfterEach
	void reset() {
		HostState.released = false;
	}

	@Test
	void theDllsHubKeysAreTheMovementKeys() {
		assertEquals(List.of("forward", "left", "back", "right", "sneak", "jump", "sprint"), HubClient.Keys.NAMES,
			"in the order of keyUp, keyLeft, keyDown, keyRight, keyShift, keyJump, keySprint");
		HubClient.Keys keys = new HubClient.Keys();
		for (String k : HubClient.Keys.NAMES) {
			assertTrue(keys.host(k, true), k);
			assertTrue(keys.down(k), k + " is held");
			assertTrue(keys.host(k, false), k);
			assertFalse(keys.down(k), k + " is up again");
		}
	}

	@Test
	void otherKeysAreNotTheHubs() {
		HubClient.Keys keys = new HubClient.Keys();
		for (String k : List.of("use", "attack", "pick", "inventory", "drop", "escape", "perspective", "hack")) {
			assertFalse(keys.host(k, true), k + " keeps its own path");
		}
	}

	@Test
	void outsideTheHubNothingIsTaken() {
		assertFalse(HubClient.key(null, "forward", true), "older paths are unaffected: ClientInput ignores the key as before");
		assertFalse(HubClient.look(null, null), "and Flight's glide look takes look");
	}
}
