package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Offline LAN logins: nobody may join under the owner's name while authentication is off. */
class LanAccessTest {
	@Test
	void theOwnersNameIsRefusedOnlyWithoutAuthentication() {
		assertTrue(LanAccess.impostor(false, "Rod", "Rod"));
		assertTrue(LanAccess.impostor(false, "Rod", "rOD"), "isSingleplayerOwner ignores case, so does the guard");
		assertFalse(LanAccess.impostor(false, "Rod", "CbGuest1"), "an offline guest with its own name joins");
		assertFalse(LanAccess.impostor(true, "Rod", "Rod"), "with authentication Mojang vouches for the name");
	}
}
