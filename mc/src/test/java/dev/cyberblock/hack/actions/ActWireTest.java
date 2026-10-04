package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The interaction map's requests, the rhack DOWN and the ui answers (megaplan task 6-I1, section 1.9 row 6). */
class ActWireTest {
	private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void requestsRoundTrip() {
		ActWire.Request car = ActWire.parse(json(ActWire.vehicle(RemoteHack.VEH_BRAKE, 42)));
		assertEquals(new ActWire.Request(RemoteHack.VEH_BRAKE, 42, null, 0L, null, 0), car);
		ActWire.Request cam = ActWire.parse(json(ActWire.camera(HOST, -2L)));
		assertEquals(HOST, cam.host());
		assertEquals(-2L, cam.dev(), "an unsigned 64-bit id survives");
		ActWire.Request blackout = ActWire.parse(json(ActWire.district(RemoteHack.BLACKOUT, HOST, 300)));
		assertEquals(300, blackout.secs());
		assertEquals(0, ActWire.parse(json(ActWire.district(RemoteHack.TRAFFIC_SWITCH, HOST, 300))).secs());
		ActWire.Request ping = ActWire.parse(json(ActWire.point(RemoteHack.PING_AREA, new Vec3(-1240.5, 64.0, 810.25))));
		assertEquals(new Vec3(-1240.5, 64.0, 810.25), ping.p());
		assertEquals(RemoteHack.PING_AREA.seconds(), ping.secs());
	}

	@Test
	void badRequestsAreNull() {
		for (String bad : List.of("{}", "{\"hack\":\"veh_fly\",\"eid\":1}", "{\"hack\":\"veh_lock\"}", "{\"hack\":\"veh_lock\",\"eid\":-1}",
			"{\"hack\":\"cam_feed\",\"host\":\"" + HOST + "\",\"dev\":\"0\"}", "{\"hack\":\"cam_feed\",\"host\":\"nope\",\"dev\":\"7\"}",
			"{\"hack\":\"cam_feed\",\"host\":\"" + HOST + "\",\"dev\":\"-7\"}",
			"{\"hack\":\"blackout\",\"host\":\"" + HOST + "\",\"secs\":59}", "{\"hack\":\"blackout\",\"host\":\"" + HOST + "\",\"secs\":301}",
			"{\"hack\":\"traffic_switch\"}", "{\"hack\":\"gridlock\",\"p\":[0,0]}", "{\"hack\":\"gridlock\",\"p\":[4e7,64,0]}",
			"{\"hack\":\"outline\",\"p\":[0,30000,0]}", "{\"hack\":7}")) {
			assertNull(ActWire.parse(json(bad)), bad);
		}

		// a blackout without secs is the default two minutes
		assertEquals(RemoteHack.BLACKOUT.seconds(), ActWire.parse(json("{\"hack\":\"blackout\",\"host\":\"" + HOST + "\"}")).secs());
	}

	@Test
	void rhackKeepsTheContractShape() {
		JsonObject car = json(ActWire.rhack(7, 9007199254740993L, RemoteHack.VEH_LOCK, null, null, 0));
		assertEquals(4, car.size());
		assertEquals("rhack", car.get("t").getAsString());
		assertEquals(7, car.get("id").getAsInt());
		assertEquals("9007199254740993", car.get("target").getAsString());
		assertEquals("veh_lock", car.get("hack").getAsString());
		JsonObject district = json(ActWire.rhack(8, 0L, RemoteHack.BLACKOUT, "Watson", null, 120));
		assertEquals("", district.get("target").getAsString());
		assertEquals("Watson", district.get("district").getAsString());
		assertEquals(120, district.get("secs").getAsInt());
		assertFalse(district.has("p"));
		JsonObject point = json(ActWire.rhack(9, 0L, RemoteHack.GRIDLOCK, null, new Vec3(1.5, 64.0, -2.0), 60));
		assertEquals(3, point.getAsJsonArray("p").size());
		assertEquals(24.0, point.get("r").getAsDouble());
		assertEquals(60, point.get("secs").getAsInt());
		assertEquals("{\"t\":\"feedreq\",\"on\":true}", ActWire.feedReq(true));
	}

	@Test
	void commsTextIsShortAndPlain() {
		ActWire.Comms c = ActWire.parseComms(json(ActWire.comms(HOST, "  meet at §cthe bar\n ", new int[] {1, 64, -3})));
		assertNotNull(c);
		assertEquals("meet at cthe bar", c.text());
		assertArrayEquals(new int[] {1, 64, -3}, c.p());
		assertEquals("", ActWire.parseComms(json(ActWire.comms(HOST, "", new int[] {0, 0, 0}))).text(), "a waypoint alone is fine");
		assertEquals(64, ActWire.parseComms(json(ActWire.comms(HOST, "x".repeat(64), null))).text().length());
		assertNull(ActWire.parseComms(json(ActWire.comms(HOST, "x".repeat(65), null))));
		assertNull(ActWire.parseComms(json(ActWire.comms(HOST, "   ", null))), "nothing to send");
		assertNull(ActWire.parseComms(json("{\"to\":\"nope\",\"text\":\"hi\"}")));
		assertNull(ActWire.parseComms(json("{\"to\":\"" + HOST + "\",\"text\":\"hi\",\"p\":[1,2]}")));
	}

	@Test
	void feedFramesAreMapColours() {
		byte[] px = new byte[ActWire.FEED_BYTES];
		px[5] = (byte) (61 * 4 + 3);
		JsonObject ok = json("{\"t\":\"feed\",\"w\":128,\"h\":128,\"px\":\"" + Base64.getEncoder().encodeToString(px) + "\"}");
		assertArrayEquals(px, ActWire.parseFeed(ok));
		byte[] top = px.clone();
		top[9] = (byte) 0xFF; // colour id 63, brightness 3: the last of the table's 64 slots
		assertNotNull(ActWire.parseFeed(json("{\"w\":128,\"h\":128,\"px\":\"" + Base64.getEncoder().encodeToString(top) + "\"}")));
		assertNull(ActWire.parseFeed(json("{\"w\":64,\"h\":128,\"px\":\"" + Base64.getEncoder().encodeToString(px) + "\"}")));
		assertNull(ActWire.parseFeed(json("{\"w\":128,\"h\":128,\"px\":\"" + Base64.getEncoder().encodeToString(new byte[100]) + "\"}")));
		assertNull(ActWire.parseFeed(json("{\"w\":128,\"h\":128,\"px\":\"not base64!\"}")));
		// a UI frame for a viewer carries the same bytes; the payload stays well under the 30000-byte UP cap
		String up = ok.toString();
		assertTrue(up.length() < 30000, "" + up.length());
		assertEquals(Base64.getEncoder().encodeToString(px), json(ActWire.feedUi(HOST, px)).get("px").getAsString());
	}

	@Test
	void uiAnswers() {
		assertEquals("{\"hack\":\"blackout\",\"ok\":false,\"reason\":\"busy\"}", ActWire.result(RemoteHack.BLACKOUT, false, "busy"));
		assertEquals("{\"eid\":12,\"name\":\"Quadra\"}", ActWire.vpanel(12, "Quadra"));
		assertEquals("{\"ids\":[1,2],\"secs\":20}", ActWire.outline(List.of(1, 2), 20));
		JsonObject cam = json(ActWire.camFeed(new Vec3(1, 2, 3), 90.0F, "Lobby"));
		assertEquals(90.0F, cam.get("yaw").getAsFloat());
		JsonObject comms = json(ActWire.commsUi("Guest", "hi", new int[] {4, 5, 6}));
		assertEquals("Guest", comms.get("from").getAsString());
		assertEquals(3, comms.getAsJsonArray("p").size());
		assertFalse(json(ActWire.commsUi("Guest", "hi", null)).has("p"));
		assertEquals(HOST, ActWire.parseHost(json(ActWire.host(HOST))));
		assertEquals(new ActWire.FeedWatch(HOST, false), ActWire.parseFeedWatch(json(ActWire.feedWatch(HOST, false))));
		assertNull(ActWire.parseFeedWatch(json("{\"host\":\"" + HOST + "\",\"on\":1}")));
	}
}
