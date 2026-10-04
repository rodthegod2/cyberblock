package dev.cyberblock.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: what a linked player's DLL hears about the hub, who may enter, and the parked answer. */
class HubRulesTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theDownMessagesAreTheContractShapes() {
		assertEquals(json("{\"t\":\"hub\",\"in\":true}"), json(HubRules.hub(true)));
		assertEquals(json("{\"t\":\"hub\",\"in\":false}"), json(HubRules.hub(false)));
		assertEquals(json("{\"t\":\"park\",\"on\":true}"), json(HubRules.park(true)));
		assertEquals(json("{\"t\":\"park\",\"on\":false}"), json(HubRules.park(false)));
	}

	@Test
	void enteringSendsHubThenPark() {
		assertEquals(List.of(HubRules.hub(true), HubRules.park(true)), HubRules.sync(true, null), "a fresh session in the hub");
		assertEquals(List.of(HubRules.hub(true), HubRules.park(true)), HubRules.sync(true, false));
	}

	@Test
	void leavingReleasesVFirstThenThePicture() {
		assertEquals(List.of(HubRules.park(false), HubRules.hub(false)), HubRules.sync(false, true));
	}

	@Test
	void nothingIsSentWhenTheDllAlreadyKnows() {
		assertEquals(List.of(), HubRules.sync(true, true));
		assertEquals(List.of(), HubRules.sync(false, false));
		assertEquals(List.of(), HubRules.sync(false, null), "a DLL starts out of the hub");
	}

	@Test
	void whoMayEnter() {
		assertNull(HubRules.enterRefusal(false, false, false, false, false), "a guest");
		assertNull(HubRules.enterRefusal(false, false, true, false, false), "an active host on foot");
		assertNull(HubRules.enterRefusal(false, false, false, true, true), "held and vehicle only matter for an active host");
		assertEquals("cyberblock.hub.missing", HubRules.enterRefusal(true, false, false, false, false));
		assertEquals("cyberblock.hub.already", HubRules.enterRefusal(false, true, false, false, false));
		assertEquals("cyberblock.hub.held", HubRules.enterRefusal(false, false, true, true, false));
		assertEquals("cyberblock.hub.vehicle", HubRules.enterRefusal(false, false, true, false, true));
	}

	@Test
	void theParkedAnswer() {
		assertEquals(new HubRules.Parked(true, true, ""), HubRules.parked(json("{\"t\":\"parked\",\"on\":true,\"ok\":true,\"reason\":\"\"}")));
		assertEquals(new HubRules.Parked(false, false, "blocked (in combat)"),
			HubRules.parked(json("{\"t\":\"parked\",\"on\":false,\"ok\":false,\"reason\":\"blocked (in combat)\"}")));
		assertEquals(new HubRules.Parked(true, true, ""), HubRules.parked(json("{\"t\":\"parked\",\"on\":true,\"ok\":true}")), "reason absent = \"\"");
		assertNull(HubRules.parked(json("{\"t\":\"parked\",\"on\":1,\"ok\":true}")));
		assertNull(HubRules.parked(json("{\"t\":\"parked\",\"on\":true}")));
		assertNull(HubRules.parked(json("{\"t\":\"parked\",\"on\":true,\"ok\":false,\"reason\":7}")));
		assertNull(HubRules.parked(json("{\"t\":\"parked\",\"on\":true,\"ok\":false,\"reason\":\"" + "x".repeat(129) + "\"}")));
	}

	@Test
	void theParkPathLineSaysWhichWayIsActive() {
		assertTrue(HubRules.parkPath(null).startsWith("unconfirmed"));
		assertTrue(HubRules.parkPath(new HubRules.Parked(true, true, "")).startsWith("confirmed"));
		assertTrue(HubRules.parkPath(new HubRules.Parked(false, false, "blocked (in a vehicle)")).contains("blocked (in a vehicle)"));
	}

	@Test
	void stationsFaceTheArrivalPoint() {
		assertEquals("south", HubRules.facingToward(0, 0, 1, 10));
		assertEquals("north", HubRules.facingToward(0, 0, -1, -10));
		assertEquals("east", HubRules.facingToward(0, 0, 10, 1));
		assertEquals("west", HubRules.facingToward(0, 0, -10, 1));
	}
}
