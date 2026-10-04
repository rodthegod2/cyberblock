package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReconnectTest {
	/** Section 1.5: after 5, 10, 20, 40 s, then every 60 s, for 10 minutes from the drop. */
	@Test
	void theBackoffSchedule() {
		int[] delays = new int[8];
		for (int i = 0; i < delays.length; i++) {
			delays[i] = Reconnect.delaySeconds(i);
		}

		assertArrayEquals(new int[] {5, 10, 20, 40, 60, 60, 60, 60}, delays);
		assertEquals(5, Reconnect.retryAt(0));
		assertEquals(75, Reconnect.retryAt(3));
		assertEquals(135, Reconnect.retryAt(4));
	}

	@Test
	void retriesStopAfterTenMinutes() {
		int last = 0;
		while (!Reconnect.gaveUp(last)) {
			last++;
		}

		assertTrue(Reconnect.retryAt(last - 1) <= 600);
		assertTrue(Reconnect.retryAt(last) > 600);
		assertEquals(12, last, "4 quick retries, then 60 s apart up to 555 s");
	}

	@Test
	void refusalsAreNotRetried() {
		List<String> refusals = List.of("You are not white-listed on this server!", "Cyberblock version mismatch");
		assertTrue(Reconnect.worthRetrying("Connection Lost, Timed out", refusals));
		assertTrue(Reconnect.worthRetrying("Connection Lost, Server closed", refusals));
		assertFalse(Reconnect.worthRetrying("Connection Lost, You are not white-listed on this server!", refusals));
		assertFalse(Reconnect.worthRetrying("CYBERBLOCK VERSION MISMATCH: server 1.1, your mod 1.0", refusals));
		assertTrue(Reconnect.worthRetrying("anything", List.of("", " ")), "a blank refusal matches nothing");
	}
}
