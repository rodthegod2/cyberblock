package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Phase 8 (8D's test of 8O's relay rows, added at the merge):the DLL's spawnresult reaches the server's listeners, but only as an answer to a spawn id the session issued. */
class SpawnResultRelayTest {
	private static final String UNSUPPORTED = "{\"t\":\"spawnresult\",\"id\":5,\"ok\":false,\"n\":0,\"reason\":\"unsupported\"}";

	@Test
	void spawnresultIsAnUpType() {
		assertTrue(RelayGuard.isUpType("spawnresult"));
	}

	@Test
	void anAnswerToAnIssuedIdPassesAndAnyOtherIsDropped() {
		RelayGuard g = new RelayGuard(1.0, 160.0, () -> 0L);
		assertEquals(1, g.check(true, "spawnresult", UNSUPPORTED, 0, 0, id -> id == 5).messages().size(), "issued id 5");
		assertTrue(g.check(true, "spawnresult", UNSUPPORTED, 0, 0, id -> id == 6).messages().isEmpty(), "id 5 was not issued");
	}

	@Test
	void itsBucketIsTenASecondWithABurstOfForty() {
		RelayGuard g = new RelayGuard(1.0, 160.0, () -> 0L);
		int accepted = 0;
		for (int i = 0; i < 50; i++) {
			accepted += g.check(true, "spawnresult", UNSUPPORTED, 0, 0, id -> true).messages().size();
		}

		assertEquals(40, accepted);
	}
}
