package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.device.Device;
import dev.cyberblock.net.Permissions.DeviceType;
import org.junit.jupiter.api.Test;

/** Plan 6-B1: results mapping: uploaded daemons to the daemon DOWN with their strength, a failure to ["alarm"], an early abort to nothing. */
class BreachResultsTest {
	private static final Device PORT = new Device(-6917529027641081846L, true, "Terminal", DeviceType.TERMINAL, 1, 64, 2, 0, 1, Device.POWERED);
	private static final Device SMALL_ID = new Device(77L, false, "Computer", DeviceType.TERMINAL, 1, 64, 2, 0, 1, 0);

	private static JsonObject down(final int request, final Device d, final BreachGame g) {
		String json = BreachResults.daemonDown(request, d, BreachResults.Outcome.of(g));
		assertNotNull(json);
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void uploadedDaemonsGoAsTheirIdsWithTheirStrength() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		for (int cell : g.puzzle().solution()) {
			g.pick(cell, 0);
		}

		JsonObject m = down(9, PORT, g);
		assertEquals("{\"t\":\"daemon\",\"id\":9,\"dev\":\"11529215046068469770\",\"list\":[\"open_door\",\"overload\"],\"strength\":2}", m.toString(),
			"protocol.md's shape, the device id as an unsigned decimal string");
		assertEquals("solved", BreachResults.Outcome.of(g).state());
	}

	@Test
	void oneShortDaemonIsStrengthOne() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		g.pick(1, 0);
		g.pick(21, 0);
		g.expire(20_000);
		JsonObject m = down(3, SMALL_ID, g);
		assertEquals("77", m.get("dev").getAsString());
		assertEquals("[\"open_door\"]", m.get("list").toString());
		assertEquals(1, m.get("strength").getAsInt());
		assertEquals("solved", BreachResults.Outcome.of(g).state(), "the timer ran out after an upload: what was uploaded counts");
	}

	@Test
	void failureAndTimeoutRaiseTheAlarm() {
		BreachGame timeout = new BreachGame(BreachGameTest.puzzle(), 0);
		timeout.pick(0, 0);
		timeout.expire(60_000);
		assertEquals("timeout", BreachResults.Outcome.of(timeout).state());
		JsonObject m = down(4, PORT, timeout);
		assertEquals("[\"alarm\"]", m.get("list").toString(), "always alone");
		assertEquals(1, m.get("strength").getAsInt());

		BreachGame untouched = new BreachGame(BreachGameTest.puzzle(), 0);
		untouched.expire(20_000);
		assertEquals("timeout", BreachResults.Outcome.of(untouched).state(), "a kept puzzle that runs out before a pick");
		assertEquals("[\"alarm\"]", down(6, PORT, untouched).get("list").toString());

		BreachGame abort = new BreachGame(BreachGameTest.puzzle(), 0);
		abort.pick(0, 0);
		abort.quit();
		assertEquals("aborted", BreachResults.Outcome.of(abort).state(), "protocol.md: walking out after a pick is a failure with state aborted");
		assertEquals("[\"alarm\"]", down(5, PORT, abort).get("list").toString());
		assertEquals(5, BreachResults.FAILURE_HEAT, "Heat +5 on the Phase 8 meter");
	}

	@Test
	void anAbortAfterAnUploadIsTheAlarmNotTheDaemon() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		g.pick(1, 0);
		g.pick(21, 0);
		g.quit();
		BreachResults.Outcome o = BreachResults.Outcome.of(g);
		assertTrue(o.failed());
		assertFalse(o.success());
		assertEquals("aborted", o.state());
		assertEquals(0, o.strength());
		assertEquals("[\"alarm\"]", down(7, PORT, g).get("list").toString(), "no open_door: an abort is not a way to cash in");
	}

	@Test
	void abortBeforeAPickSendsNothing() {
		BreachGame g = new BreachGame(BreachGameTest.puzzle(), 0);
		g.quit();
		BreachResults.Outcome o = BreachResults.Outcome.of(g);
		assertTrue(o.cancelled());
		assertEquals("aborted", o.state());
		assertNull(BreachResults.daemonDown(1, PORT, o));
	}
}
