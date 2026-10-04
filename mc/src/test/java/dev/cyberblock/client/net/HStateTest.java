package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HStateTest {
	private static final long MS = 1_000_000L;

	private static HState.Snapshot sample(final boolean air, final double feet) {
		return new HState.Snapshot(true, false, air, false, false, false, false, false, 7, feet);
	}

	/** Section 1.2: 5 Hz, and on a change (never faster than the server's 10 per second). */
	@Test
	void sentAtFiveHertzAndOnChange() {
		HState h = new HState();
		assertTrue(h.due(sample(false, 10.0), 0), "the first sample");
		assertFalse(h.due(sample(false, 10.0), 50 * MS), "no change, not yet 200 ms");
		assertFalse(h.due(sample(true, 10.0), 50 * MS), "a change within 100 ms waits");
		assertTrue(h.due(sample(true, 10.0), 100 * MS), "the change, 100 ms on");
		assertFalse(h.due(sample(true, 10.0), 250 * MS));
		assertTrue(h.due(sample(true, 10.0), 300 * MS), "200 ms without a change");
	}

	@Test
	void feetWobbleIsNotAChange() {
		HState h = new HState();
		h.due(sample(false, 10.0), 0);
		assertFalse(h.due(sample(false, 10.004), 150 * MS));
		assertTrue(h.due(sample(false, 10.5), 150 * MS));
		assertTrue(h.due(sample(false, Double.NaN), 300 * MS));
		assertFalse(h.due(sample(false, Double.NaN), 450 * MS), "NaN equals NaN here");
	}

	@Test
	void resetSendsTheNextAtOnce() {
		HState h = new HState();
		h.due(sample(false, 1.0), 0);
		h.reset();
		assertTrue(h.due(sample(false, 1.0), 10 * MS));
	}

	@Test
	void theMessageHasTheContractFields() {
		JsonObject m = JsonParser.parseString(new HState.Snapshot(true, true, false, true, false, true, false, true, 99, 12.25).json()).getAsJsonObject();
		assertEquals("hstate", m.get("t").getAsString());
		for (String f : new String[] {"active", "vehicle", "air", "cr", "held", "fly", "launch", "seat"}) {
			assertTrue(m.get(f).getAsJsonPrimitive().isBoolean(), f);
		}

		assertEquals(63, m.get("lv").getAsInt(), "clamped to 0..63");
		assertEquals(12.25, m.get("feetCpZ").getAsDouble());
		assertFalse(JsonParser.parseString(sample(false, Double.NaN).json()).getAsJsonObject().has("feetCpZ"), "unknown feet: absent");
	}
}
