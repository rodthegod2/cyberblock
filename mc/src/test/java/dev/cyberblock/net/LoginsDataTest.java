package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginsDataTest {
	private final UUID alice = new UUID(1, 1), bob = new UUID(2, 2);

	@Test
	void aVerifiedPlayerIsRememberedWhileThePasswordStaysTheSame() {
		LoginsData d = new LoginsData();
		assertFalse(d.sync("hunter2"), "the first password drops nothing");
		d.add(this.alice);
		assertTrue(d.verified(this.alice));
		assertFalse(d.verified(this.bob));
		assertFalse(d.sync("hunter2"), "a restart with the same password");
		assertTrue(d.verified(this.alice));
		assertEquals(1, d.count());
	}

	@Test
	void aNewPasswordLocksEveryoneAgain() {
		LoginsData d = new LoginsData();
		d.sync("hunter2");
		d.add(this.alice);
		d.add(this.bob);
		assertTrue(d.sync("hunter3"));
		assertFalse(d.verified(this.alice));
		assertFalse(d.verified(this.bob));
		assertEquals(0, d.count());
		d.add(this.alice);
		assertFalse(d.sync("hunter3"));
		assertTrue(d.verified(this.alice));
	}

	@Test
	void turningThePasswordOffKeepsTheRecordsForTheSamePassword() {
		LoginsData d = new LoginsData();
		d.sync("hunter2");
		d.add(this.alice);
		assertFalse(d.sync(""), "off: nothing changes");
		assertFalse(d.sync("hunter2"));
		assertTrue(d.verified(this.alice));
	}

	@Test
	void resetClearsTheList() {
		LoginsData d = new LoginsData();
		d.sync("hunter2");
		d.add(this.alice);
		d.add(this.bob);
		d.add(this.bob);
		assertEquals(2, d.reset());
		assertEquals(0, d.count());
		assertEquals(0, d.reset());
		assertFalse(d.sync("hunter2"), "the password itself is unchanged");
	}

	@Test
	void theFingerprintIsSaltedAndNeverThePassword() {
		String salt = "00112233445566778899aabbccddeeff";
		String a = LoginsData.fingerprint(salt, "hunter2");
		assertEquals(a, LoginsData.fingerprint(salt, "hunter2"));
		assertNotEquals(a, LoginsData.fingerprint("ffeeddccbbaa99887766554433221100", "hunter2"), "another world, another fingerprint");
		assertNotEquals(a, LoginsData.fingerprint(salt, "hunter3"));
		assertEquals(64, a.length());
		assertFalse(a.contains("hunter2"));
	}

	@Test
	void theRecordsSurviveASaveAndLoadWithoutThePassword() {
		LoginsData d = new LoginsData();
		d.sync("hunter2");
		d.add(this.alice);
		JsonElement saved = LoginsData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
		assertFalse(saved.toString().contains("hunter2"), saved.toString());
		LoginsData back = LoginsData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		assertTrue(back.verified(this.alice));
		assertFalse(back.sync("hunter2"), "the loaded fingerprint matches the same password");
		assertTrue(back.verified(this.alice));
		assertTrue(back.sync("other"), "and not another one");
		LoginsData empty = LoginsData.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
		assertEquals(0, empty.count());
	}
}
