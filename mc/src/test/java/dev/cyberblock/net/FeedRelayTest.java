package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.client.net.Relay;
import dev.cyberblock.hack.actions.ActWire;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Phase 6 (section 1.9 row 6): the DLL's photo `feed` is BOTH on the host's client and reaches the server at 2 a second, burst 4. */
class FeedRelayTest {
	private static final String FEED = "{\"t\":\"feed\",\"w\":128,\"h\":128,\"px\":\"" + Base64.getEncoder().encodeToString(new byte[ActWire.FEED_BYTES]) + "\"}";

	@Test
	void theClientRelaysTheFeed() {
		assertEquals(Relay.Route.BOTH, Relay.route("feed"));
		assertEquals(Relay.Route.LOCAL, Relay.route("feedreq"), "feedreq is DOWN only");
		assertEquals(1, Relay.split("feed", com.google.gson.JsonParser.parseString(FEED).getAsJsonObject(), FEED, Payloads.UP_CAP).size(),
			"a whole frame fits one payload");
	}

	@Test
	void theServerTakesTwoASecondWithABurstOfFour() {
		AtomicLong now = new AtomicLong(1_000_000L);
		RelayGuard g = new RelayGuard(1.0, 160.0, now::get);
		assertTrue(RelayGuard.isUpType("feed"));
		for (int i = 0; i < 4; i++) {
			RelayGuard.Verdict v = g.check(true, "feed", FEED, 0, 0, id -> false);
			assertNotNull(v.message(), "frame " + i);
			assertNull(v.kick());
		}

		assertNull(g.check(true, "feed", FEED, 0, 0, id -> false).message(), "the fifth at once is over the burst");
		now.addAndGet(1000L);
		assertNotNull(g.check(true, "feed", FEED, 0, 0, id -> false).message(), "a second later the bucket has refilled");
		assertFalse(g.check(false, "feed", FEED, 0, 0, id -> false).message() != null, "a guest's feed is dropped");
	}
}
