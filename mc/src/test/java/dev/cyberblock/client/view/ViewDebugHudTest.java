package dev.cyberblock.client.view;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Megaplan Q-V4: cam.vdbg parsing (a bad vdbg is ignored, never the cam) and the 5 s log line. */
class ViewDebugHudTest {
	private static JsonObject cam(final String vdbg) {
		return JsonParser.parseString("{'t':'cam','p':[0,64,0],'r':[0,0,0]" + (vdbg == null ? "" : ",'vdbg':" + vdbg) + "}").getAsJsonObject();
	}

	@Test
	void validVdbgIsCollideSmoothAge() {
		assertArrayEquals(new double[] {3.2, 2.85, 14.0}, ViewDebugHud.parseVdbg(cam("[3.2,2.85,14.0]")), 1e-12);
		assertArrayEquals(new double[] {0.0, 100.0, 60000.0}, ViewDebugHud.parseVdbg(cam("[0,100,60000]")), 1e-12);
	}

	@Test
	void absentOrBadVdbgIsNone() {
		assertNull(ViewDebugHud.parseVdbg(cam(null)));
		assertNull(ViewDebugHud.parseVdbg(cam("[3.2,2.85]")), "two numbers");
		assertNull(ViewDebugHud.parseVdbg(cam("[3.2,2.85,14,1]")), "four numbers");
		assertNull(ViewDebugHud.parseVdbg(cam("[-1,2.85,14]")), "negative");
		assertNull(ViewDebugHud.parseVdbg(cam("[101,2.85,14]")), "over 100 m");
		assertNull(ViewDebugHud.parseVdbg(cam("[3,2.85,60001]")), "over a minute old");
		assertNull(ViewDebugHud.parseVdbg(cam("[NaN,2.85,14]")), "not a number");
		assertNull(ViewDebugHud.parseVdbg(cam("['far',2.85,14]")), "a string");
		assertNull(ViewDebugHud.parseVdbg(cam("{'collide':3}")), "not an array");
	}

	@Test
	void theLogLineStartsWithTheSection114Format() {
		String line = ViewDebugHud.logLine(8.33, 21.0, 14.25, 1.0, 3.2, 2.85);
		assertTrue(line.startsWith("view: frame 8.3 pose age 14.3 interp 1.00 dist 3.20 collide 2.85"), line);
		assertTrue(ViewDebugHud.logLine(8.0, 9.0, 10.0, 1.0, 3.0, Double.NaN).contains("collide n/a"));
	}
}
