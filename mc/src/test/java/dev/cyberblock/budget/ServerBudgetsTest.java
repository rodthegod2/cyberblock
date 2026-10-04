package dev.cyberblock.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Megaplan 9-F1: what a message costs under the budgets, and the minute's line. */
class ServerBudgetsTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void groundCostsItsCells() {
		assertEquals(1 + 10 + 3, ServerBudgets.cells(json("{\"t\":\"ground\",\"c\":[0,0,63,63, 1,0,63,72, 2,0,60,62]}")));
		assertEquals(1, ServerBudgets.cells(json("{\"t\":\"clear\"}")));
		assertEquals(1, ServerBudgets.cells(json("{\"t\":\"ground\",\"c\":[0,0,63]}")), "malformed: its handler drops it");
		assertEquals(1, ServerBudgets.cells(json("{\"t\":\"ground\",\"c\":[0,0,\"a\",63]}")));
		assertEquals(1, ServerBudgets.cells(json("{\"t\":\"unground\",\"c\":[0,0,70,63]}")), "inverted: at least 1");
		assertEquals(1_000_000, ServerBudgets.cells(json("{\"t\":\"ground\",\"c\":[0,0,-2000000000,2000000000]}")));
	}

	@Test
	void aDeviceListCostsItsEntries() {
		assertEquals(3, ServerBudgets.entries(json("{\"t\":\"devices\",\"list\":[{},{},{}]}")));
		assertEquals(1, ServerBudgets.entries(json("{\"t\":\"devices\",\"list\":[]}")));
		assertEquals(1, ServerBudgets.entries(json("{\"t\":\"devstate\",\"id\":\"5\"}")));
	}

	@Test
	void theMinutesLineOnlyWhenSomethingWaited() {
		assertNull(ServerBudgets.report(new long[4], new long[4], new long[2]));
		assertEquals("budget: last 60 s: replica 12 messages (30000 cells) waited (longest queue 7), devices 1 messages (64 updates) waited (longest "
			+ "queue 1), DOWN 0 messages held to the next tick (most sent in one tick 9)",
			ServerBudgets.report(new long[] {12, 30000, 7, 0}, new long[] {1, 64, 1, 0}, new long[] {0, 9}));
	}
}
