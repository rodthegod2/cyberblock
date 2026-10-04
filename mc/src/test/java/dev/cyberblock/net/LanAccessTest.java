package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import org.junit.jupiter.api.Test;

/** Offline LAN logins: nobody may join under the owner's name, and no offline name is verified from a stored /login, while authentication is off. */
class LanAccessTest {
	@Test
	void theOwnersNameIsRefusedOnlyWithoutAuthentication() {
		assertTrue(LanAccess.impostor(false, "Rod", "Rod"));
		assertTrue(LanAccess.impostor(false, "Rod", "rOD"), "isSingleplayerOwner ignores case, so does the guard");
		assertFalse(LanAccess.impostor(false, "Rod", "CbGuest1"), "an offline guest with its own name joins");
		assertFalse(LanAccess.impostor(true, "Rod", "Rod"), "with authentication Mojang vouches for the name");
	}

	@Test
	void anOfflineUuidIsNeverVerifiedFromStoredLoginsWithoutAuthentication() {
		UUID offline = UUIDUtil.createOfflinePlayerUUID("Alice");
		assertFalse(LanAccess.storedLoginTrusted(false, offline, "Alice"), "anyone typing Alice gets this UUID: a stored login proves nothing");
		assertTrue(LanAccess.storedLoginTrusted(true, offline, "Alice"), "with authentication the stored record counts");
		UUID account = UUID.fromString("4a5b6c7d-1234-4abc-8def-0123456789ab");
		assertTrue(LanAccess.storedLoginTrusted(false, account, "Alice"), "a real account's UUID (joined with authentication) still counts");
	}
}
