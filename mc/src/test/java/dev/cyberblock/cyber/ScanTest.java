package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ScanTest {
	private static final String GOOD = "{\"t\":\"scaninfo\",\"id\":7,\"ok\":true,\"target\":\"18446744073709551615\",\"name\":\"NCPD Officer\","
		+ "\"faction\":\"NCPD\",\"threat\":\"high\",\"hp\":0.75,\"device\":false,\"p\":[1.5,64,-3],\"size\":[0.6,1.8]}";

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static JsonObject good() {
		return json(GOOD);
	}

	@Test
	void throttleSendsAtOnceThenOnChangeOrEverySecond() {
		ScanThrottle t = new ScanThrottle();
		assertTrue(t.shouldSend(0, 5));
		assertFalse(t.shouldSend(200, 5));
		assertFalse(t.shouldSend(999, 5));
		assertTrue(t.shouldSend(1000, 5)); // the same target again after a second
		assertTrue(t.shouldSend(1200, 6)); // a new target
		assertTrue(t.shouldSend(1400, -1)); // nothing under the crosshair is news too
	}

	@Test
	void throttleNeverExceedsFivePerSecond() {
		ScanThrottle t = new ScanThrottle();
		int sent = 0;
		for (long ms = 0; ms < 1000; ms += 50) {
			if (t.shouldSend(ms, ms)) { // a different target every pick
				sent++;
			}
		}

		assertEquals(5, sent);
	}

	@Test
	void throttleResetSendsTheNextHoldAtOnce() {
		ScanThrottle t = new ScanThrottle();
		assertTrue(t.shouldSend(0, 5));
		t.reset();
		assertTrue(t.shouldSend(50, 5));
	}

	/** Plan retest2 C1: Kiroshi optics give an 80-block scan ray, otherwise 48. */
	@Test
	void scanRangeWithAndWithoutKiroshi() {
		assertEquals(48.0, Hacking.scanRange(java.util.Set.of()));
		assertEquals(48.0, Hacking.scanRange(java.util.Set.of("mantis_blades", "cyberdeck")));
		assertEquals(80.0, Hacking.scanRange(java.util.Set.of("kiroshi_optics")));
	}

	@Test
	void scanJson() {
		assertEquals("{\"t\":\"scan\",\"id\":3,\"target\":\"42\"}", CyberWire.scan(3, 42L));
		assertEquals("{\"t\":\"scan\",\"id\":4,\"target\":null}", CyberWire.scan(4, 0L));
		assertEquals("{\"t\":\"scan\",\"id\":5,\"target\":\"18446744073709551615\"}", CyberWire.scan(5, -1L));
	}

	@Test
	void parsesAGoodReply() {
		ScanInfo s = ScanInfo.parse(good());
		assertNotNull(s);
		assertEquals(7, s.id());
		assertTrue(s.ok());
		assertEquals(-1L, s.target());
		assertEquals("NCPD Officer", s.name());
		assertEquals("NCPD", s.faction());
		assertEquals("high", s.threat());
		assertEquals(0.75F, s.hp());
		assertFalse(s.device());
		assertEquals(1.5, s.x());
		assertEquals(64.0, s.y());
		assertEquals(-3.0, s.z());
		assertEquals(0.6F, s.w());
		assertEquals(1.8F, s.h());
	}

	@Test
	void parsesNothingThere() {
		ScanInfo s = ScanInfo.parse(json("{\"t\":\"scaninfo\",\"id\":8,\"ok\":false,\"target\":\"0\",\"name\":\"\",\"faction\":\"\",\"threat\":\"none\","
			+ "\"hp\":0,\"device\":false,\"p\":[0,0,0],\"size\":[0,0]}"));
		assertNotNull(s);
		assertFalse(s.ok());
		assertEquals(8, s.id());
		assertNotNull(ScanInfo.parse(json("{\"id\":9,\"ok\":false}")));
	}

	@Test
	void rejectsBrokenReplies() {
		String[][] breaks = {
			{"id", "-1"}, {"id", "\"x\""}, {"ok", "\"yes\""},
			{"target", "\"-5\""}, {"target", "\"12a\""}, {"target", "\"0\""}, {"target", "\"184467440737095516150\""}, {"target", "null"},
			{"name", "\"" + "n".repeat(65) + "\""}, {"faction", "\"" + "f".repeat(65) + "\""},
			{"threat", "\"deadly\""}, {"hp", "1.5"}, {"hp", "-0.1"}, {"hp", "\"NaN\""},
			{"p", "[1,2]"}, {"p", "[1,2,3e8]"}, {"p", "[\"Infinity\",0,0]"}, {"size", "[1]"}, {"size", "[-1,2]"}, {"size", "[1,100]"},
		};
		for (String[] b : breaks) {
			JsonObject m = good();
			m.add(b[0], JsonParser.parseString(b[1]));
			assertNull(ScanInfo.parse(m), b[0] + "=" + b[1]);
		}

		for (String field : new String[]{"id", "ok", "target", "name", "faction", "threat", "hp", "p", "size"}) {
			JsonObject m = good();
			m.remove(field);
			assertNull(ScanInfo.parse(m), "without " + field);
		}

		JsonObject noDevice = good();
		noDevice.remove("device");
		assertNotNull(ScanInfo.parse(noDevice));
		assertEquals(64, ScanInfo.parse(json(GOOD.replace("NCPD Officer", "n".repeat(64)))).name().length());
	}
}
