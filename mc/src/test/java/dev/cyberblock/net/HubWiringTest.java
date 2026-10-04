package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Plan 7-H1: the server says it has the hub, and takes the DLL's parked answers (Phase 3's descopes: Phase 7 adds the relay). */
class HubWiringTest {
	@Test
	void theServerHasTheHubFeature() {
		assertEquals(Payloads.FEATURE_HUB, ServerNet.FEATURES & Payloads.FEATURE_HUB, "hs_ack.features bit 16");
	}

	@Test
	void parkedIsAnUpTypeWithItsOwnBucket() {
		assertTrue(RelayGuard.isUpType("parked"));
		RelayGuard g = new RelayGuard(1.0, 160.0, () -> 0L);
		String parked = "{\"t\":\"parked\",\"on\":true,\"ok\":true,\"reason\":\"\"}";
		int accepted = 0;
		for (int i = 0; i < 6; i++) {
			accepted += g.check(true, "parked", parked, 0, 0, id -> false).messages().size();
		}

		assertEquals(4, accepted, "burst 4, no request id needed");
	}
}
