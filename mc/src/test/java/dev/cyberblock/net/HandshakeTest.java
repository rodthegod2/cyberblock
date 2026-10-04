package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HandshakeTest {
	private static Handshake.Result decide(final int proto, final String mod, final String dll, final boolean wantHost, final Handshake.HostRefusal refusal,
		final int others, final int max) {
		return Handshake.decide(new Handshake.Input(proto, mod, dll, wantHost, "1.0.0", refusal, others, max));
	}

	@Test
	void aLinkedHostOfTheSameVersionHosts() {
		Handshake.Result r = decide(1, "1.0.0", "1.0.0", true, null, 0, 1);
		assertTrue(r.ok());
		assertTrue(r.host());
		assertEquals("", r.reason());
	}

	@Test
	void withoutADllOrWithoutWantHostItIsAGuest() {
		assertEquals("guest", decide(1, "1.0.0", "", true, null, 0, 1).role());
		assertEquals("guest", decide(1, "1.0.0", "1.0.0", false, null, 0, 1).role());
		assertEquals("", decide(1, "1.0.0", "", false, null, 0, 1).reason());
	}

	@Test
	void aProtocolOrMajorMinorMismatchIsRefused() {
		Handshake.Result proto = decide(2, "1.0.0", "", false, null, 0, 1);
		assertFalse(proto.ok());
		assertEquals("Cyberblock version mismatch: server 1.0.0, your mod 1.0.0. Update from the modpack.", proto.reason());
		Handshake.Result minor = decide(1, "1.1.0", "", false, null, 0, 1);
		assertFalse(minor.ok());
		assertEquals("Cyberblock version mismatch: server 1.0.0, your mod 1.1.0. Update from the modpack.", minor.reason());
		assertTrue(decide(1, "1.0.7", "", false, null, 0, 1).ok(), "a patch difference is fine");
	}

	@Test
	void aDllOfAnotherVersionPlaysAsAGuestWithTheReason() {
		Handshake.Result r = decide(1, "1.0.0", "0.9.0", true, null, 0, 1);
		assertTrue(r.ok());
		assertFalse(r.host());
		assertEquals("Cyberblock: your Cyberpunk plugin is 0.9.0 but your mod is 1.0.0; you can play as a guest. Reinstall both from the same release.",
			r.reason());
	}

	@Test
	void maxHostsTurnsTheNextHostIntoAGuest() {
		Handshake.Result r = decide(1, "1.0.0", "1.0.0", true, null, 1, 1);
		assertTrue(r.ok());
		assertFalse(r.host());
		assertEquals("Cyberblock: another Cyberpunk host is already active (max 1)", r.reason());
		assertTrue(decide(1, "1.0.0", "1.0.0", true, null, 1, 2).host());
		assertEquals("Cyberblock: another Cyberpunk host is already active (max 2)", decide(1, "1.0.0", "1.0.0", true, null, 2, 2).reason());
	}

	@Test
	void hostRulesGiveTheirReason() {
		for (Handshake.HostRefusal why : Handshake.HostRefusal.values()) {
			Handshake.Result r = decide(1, "1.0.0", "1.0.0", true, why, 0, 1);
			assertTrue(r.ok());
			assertFalse(r.host());
			assertEquals(why.reason(), r.reason());
			assertTrue(r.reason().startsWith("Cyberblock: "));
		}
	}

	@Test
	void theVersionRuleComesFirst() {
		// a mismatched mod is refused even when it could host
		assertFalse(decide(1, "2.0.0", "2.0.0", true, null, 0, 1).ok());
	}

	@Test
	void theModpackMessageNamesTheHowTo() {
		assertEquals("This server needs the Cyberblock modpack: see the pinned message", Handshake.needsMod("see the pinned message"));
		assertEquals("This server needs the Cyberblock modpack.", Handshake.needsMod(""));
	}

	@Test
	void majorMinor() {
		assertEquals("1.0", Handshake.majorMinor("1.0.3"));
		assertEquals("1.0", Handshake.majorMinor("1.0"));
		assertEquals("unknown", Handshake.majorMinor("unknown"));
	}

	@Test
	void timeoutIsTenSeconds() {
		assertEquals(200, Handshake.TIMEOUT_TICKS);
	}
}
