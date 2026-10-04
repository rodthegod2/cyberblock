package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiRoutesTest {
	@Test
	void aListedTypeGoesToItsHandlerAndOthersAreLeftToTheCyberTrack() {
		List<String> got = new ArrayList<>();
		UiRoutes.listen("zz_test_a", got::add);
		assertTrue(UiRoutes.dispatch("zz_test_a", "{\"x\":1}"));
		assertEquals(List.of("{\"x\":1}"), got);
		assertFalse(UiRoutes.dispatch("scaninfo", "{}"), "not listed: CyberLink handles it");
		assertThrows(IllegalStateException.class, () -> UiRoutes.listen("zz_test_a", got::add), "one handler per type");
	}

	@Test
	void aHandlerThatThrowsIsNeverFatal() {
		UiRoutes.listen("zz_test_b", json -> {
			throw new IllegalArgumentException("bad");
		});
		assertTrue(UiRoutes.dispatch("zz_test_b", "{}"));
	}
}
