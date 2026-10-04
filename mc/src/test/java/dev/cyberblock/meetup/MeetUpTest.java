package dev.cyberblock.meetup;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** Plan 3-E2: the Meet Up rules, the vteleport request and the vteleported answer. */
class MeetUpTest {
	@Test
	void theRequestRefusals() {
		assertNull(MeetUp.refusal(false, 0, false, false, null));
		assertEquals("cyberblock.meetup.self", MeetUp.refusal(true, 0, false, false, null));
		assertEquals("cyberblock.meetup.cooldown", MeetUp.refusal(false, 12, false, false, null));
		assertEquals("cyberblock.meetup.held", MeetUp.refusal(false, 0, true, false, null));
		assertEquals("cyberblock.meetup.vehicle", MeetUp.refusal(false, 0, false, true, null));
		// Phase 8's match-zone rule refuses with its own key
		assertEquals("cyberblock.meetup.zone", MeetUp.refusal(false, 0, false, false, "cyberblock.meetup.zone"));
		assertNull(MeetUp.matchZoneRefusal(null, null), "no match zones before Phase 8");
	}

	@Test
	void theCooldownIsThirtySeconds() {
		assertEquals(0, MeetUp.cooldownLeft(null, 1_000_000L));
		assertEquals(30, MeetUp.cooldownLeft(1_000_000L, 1_000_000L));
		assertEquals(1, MeetUp.cooldownLeft(1_000_000L, 1_029_500L));
		assertEquals(0, MeetUp.cooldownLeft(1_000_000L, 1_030_000L));
	}

	@Test
	void aRequestCanBeAcceptedForSixtySeconds() {
		assertFalse(MeetUp.expired(1_000L, 1_000L + 59_999L, MeetUp.ACCEPT_MS));
		assertTrue(MeetUp.expired(1_000L, 1_000L + 60_000L, MeetUp.ACCEPT_MS));
	}

	@Test
	void theVteleportRequest() {
		JsonObject m = JsonParser.parseString(MeetUp.vteleport(7, -1230.5, 65.0, 820.25, 45.0f)).getAsJsonObject();
		assertEquals("vteleport", m.get("t").getAsString(), "t comes first for the client's quick type read");
		assertTrue(MeetUp.vteleport(7, 0, 0, 0, 0).startsWith("{\"t\":\"vteleport\""));
		assertEquals(7, m.get("id").getAsInt());
		assertEquals(-1230.5, m.getAsJsonArray("p").get(0).getAsDouble());
		assertEquals(65.0, m.getAsJsonArray("p").get(1).getAsDouble());
		assertEquals(820.25, m.getAsJsonArray("p").get(2).getAsDouble());
		assertEquals(45.0, m.get("yaw").getAsDouble());
		assertEquals("meetup", m.get("why").getAsString());
	}

	@Test
	void aGoodAnswer() {
		MeetUp.Answer a = MeetUp.answer(json("{\"t\":\"vteleported\",\"id\":7,\"ok\":true,\"reason\":\"\",\"p\":[-1230.5,64.0,820.5]}"));
		assertNotNull(a);
		assertEquals(7, a.id());
		assertTrue(a.ok());
		assertEquals("", a.reason());
		assertArrayEquals(new double[] {-1230.5, 64.0, 820.5}, a.p());
	}

	@Test
	void aFailureNeedsNoPosition() {
		MeetUp.Answer a = MeetUp.answer(json("{\"t\":\"vteleported\",\"id\":3,\"ok\":false,\"reason\":\"no ground\"}"));
		assertNotNull(a);
		assertFalse(a.ok());
		assertEquals("no ground", a.reason());
		assertNull(a.p());
	}

	@Test
	void badAnswersAreDropped() {
		for (String bad : new String[] {"{\"ok\":true,\"reason\":\"\",\"p\":[0,0,0]}", "{\"id\":1.5,\"ok\":true,\"reason\":\"\",\"p\":[0,0,0]}",
			"{\"id\":1,\"reason\":\"\",\"p\":[0,0,0]}", "{\"id\":1,\"ok\":\"yes\",\"reason\":\"\",\"p\":[0,0,0]}",
			"{\"id\":1,\"ok\":true,\"reason\":\"\"}", "{\"id\":1,\"ok\":true,\"reason\":\"\",\"p\":[0,0]}",
			"{\"id\":1,\"ok\":true,\"reason\":\"\",\"p\":[0,\"a\",0]}", "{\"id\":1,\"ok\":true,\"reason\":\"\",\"p\":[0,0,4e7]}",
			"{\"id\":1,\"ok\":false,\"reason\":3}", "{\"id\":1,\"ok\":false,\"reason\":\"" + "x".repeat(129) + "\"}"}) {
			assertNull(MeetUp.answer(json(bad)), bad);
		}

		assertNotNull(MeetUp.answer(json("{\"id\":1,\"ok\":false}")), "no reason reads as the empty reason");
	}

	@Test
	void farAnswersLandOnTheRequestedSpot() {
		double[] asked = {100.0, 64.0, 100.0};
		assertArrayEquals(new double[] {101.0, 70.0, 99.0}, MeetUp.landing(new double[] {101.0, 70.0, 99.0}, asked));
		assertArrayEquals(asked, MeetUp.landing(new double[] {200.0, 64.0, 100.0}, asked));
		assertArrayEquals(asked, MeetUp.landing(null, asked));
	}

	@Test
	void theReasonsShowAsText() {
		assertEquals("cyberblock.meetup.reason.no_ground", key(MeetUp.reason("no ground")));
		assertEquals("cyberblock.meetup.reason.timeout", key(MeetUp.reason("timeout")));
		assertEquals("cyberblock.meetup.reason.blocked", key(MeetUp.reason("blocked (in a vehicle)")));
		assertEquals("in a vehicle", ((TranslatableContents) MeetUp.reason("blocked (in a vehicle)").getContents()).getArgs()[0]);
		assertEquals("cyberblock.meetup.reason.unknown", key(MeetUp.reason("")));
		assertEquals("something new", MeetUp.reason("something new").getString());
	}

	private static String key(final Component c) {
		return ((TranslatableContents) c.getContents()).getKey();
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}
}
