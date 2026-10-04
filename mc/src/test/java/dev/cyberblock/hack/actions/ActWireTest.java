package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
		assertEquals(new ActWire.Request(RemoteHack.VEH_BRAKE, 42, null, 0L, null, RemoteHack.VEHICLE_SECONDS), car);
		ActWire.Request cam = ActWire.parse(json(ActWire.camera(HOST, -2L)));
		assertEquals(HOST, cam.host());
		assertEquals(-2L, cam.dev(), "an unsigned 64-bit id survives");
		ActWire.Request blackout = ActWire.parse(json(ActWire.district(RemoteHack.BLACKOUT, HOST, 300)));
		assertEquals(300, blackout.secs());
		assertEquals(60, ActWire.parse(json(ActWire.district(RemoteHack.TRAFFIC_SWITCH, HOST, 300))).secs(), "only a blackout's length is the player's");
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
		JsonObject car = json(ActWire.rhack(7, 9007199254740993L, RemoteHack.VEH_LOCK, null, RemoteHack.VEH_LOCK.downSeconds(0)));
		assertEquals(Set.of("t", "id", "target", "hack", "secs"), car.keySet());
		assertEquals("9007199254740993", car.get("target").getAsString());
		assertEquals(30, car.get("secs").getAsInt(), "every vehicle effect is timed: the server sends 30");
		JsonObject cam = json(ActWire.rhack(8, -2L, RemoteHack.CAM_FEED, null, 0));
		assertEquals(Set.of("t", "id", "target", "hack"), cam.keySet());
		assertEquals("18446744073709551614", cam.get("target").getAsString());
		JsonObject blackout = json(ActWire.rhack(9, 0L, RemoteHack.BLACKOUT, new Vec3(1.5, 64.0, -2.0), RemoteHack.BLACKOUT.downSeconds(240)));
		assertTrue(blackout.get("target").isJsonNull(), "an area hack's target is JSON null, not an empty string");
		assertEquals(Set.of("t", "id", "target", "hack", "p", "r", "secs"), blackout.keySet());
		assertEquals("150", blackout.get("r").toString(), "r is a whole number");
		assertEquals(240, blackout.get("secs").getAsInt());
		JsonObject ping = json(ActWire.rhack(10, 0L, RemoteHack.PING_AREA, new Vec3(0, 64, 0), RemoteHack.PING_AREA.downSeconds(0)));
		assertEquals(0, ping.get("secs").getAsInt(), "a ping is a one-shot");
		assertThrows(IllegalArgumentException.class, () -> ActWire.rhack(11, 0L, RemoteHack.GRIDLOCK, null, 60));
		assertEquals("{\"t\":\"feedreq\",\"on\":true}", ActWire.feedReq(true));
	}

	/**
	 * shared/rhack-vectors.json: the server's DOWN `rhack` for all eleven hacks, built here from each case's inputs, must equal the
	 * vector and obey shared/protocol.md's rules (the port of tests/test_phase6_protocol.py rhack_ok); pytest checks the same vectors
	 * against rhack_ok and the fake DLL (tests/test_rhack_vectors.py).
	 */
	@Test
	void everyHackMatchesTheSharedVectorsAndTheProtocolRules() throws IOException {
		JsonObject vectors = json(Files.readString(Path.of("..", "shared", "rhack-vectors.json"), StandardCharsets.UTF_8));
		Set<RemoteHack> seen = new HashSet<>();
		for (JsonElement e : vectors.getAsJsonArray("cases")) {
			JsonObject c = e.getAsJsonObject();
			RemoteHack hack = RemoteHack.byWire(c.get("hack").getAsString());
			assertNotNull(hack, c.toString());
			seen.add(hack);
			long target = c.get("target").isJsonNull() ? 0L : Long.parseUnsignedLong(c.get("target").getAsString());
			Vec3 p = c.has("p") ? ActWire.position(c.get("p")) : null;
			int asked = c.has("asked") ? c.get("asked").getAsInt() : hack.seconds();
			String built = ActWire.rhack(c.get("id").getAsInt(), target, hack, p, hack.downSeconds(asked));
			assertEquals(c.getAsJsonObject("down"), json(built), hack.wire());
			assertTrue(rhackOk(json(built)), built);
		}

		assertEquals(Set.of(RemoteHack.values()), seen, "every hack has a vector");
	}

	@Test
	void theProtocolRulesRejectTheOldShapes() {
		// the shapes before the fix: "" for an area target, a district and no p or r, a double r, a vehicle hack without secs, a timed hack at 0 s
		for (String old : List.of("{\"t\":\"rhack\",\"id\":1,\"target\":\"\",\"hack\":\"blackout\",\"district\":\"Watson\",\"secs\":120}",
			"{\"t\":\"rhack\",\"id\":1,\"target\":null,\"hack\":\"gridlock\",\"p\":[0,64,0],\"r\":24.0,\"secs\":60}",
			"{\"t\":\"rhack\",\"id\":1,\"target\":\"5\",\"hack\":\"veh_lock\"}",
			"{\"t\":\"rhack\",\"id\":1,\"target\":null,\"hack\":\"traffic_switch\",\"p\":[0,64,0],\"r\":150,\"secs\":0}")) {
			assertFalse(rhackOk(json(old)), old);
		}
	}

	/** tests/test_phase6_protocol.py rhack_ok, in Java: exact key sets, ids, whole `r` 1..150 and `secs` per hack. */
	private static boolean rhackOk(final JsonObject m) {
		Set<String> base = Set.of("t", "id", "target", "hack");
		if (!m.keySet().containsAll(base) || !whole(m.get("id"), 0, Integer.MAX_VALUE) || !m.get("hack").isJsonPrimitive()) {
			return false;
		}

		RemoteHack hack = RemoteHack.byWire(m.get("hack").getAsString());
		if (hack == null) {
			return false;
		}

		if (hack.area()) {
			boolean timed = hack == RemoteHack.TRAFFIC_SWITCH || hack == RemoteHack.GRIDLOCK || hack == RemoteHack.BLACKOUT;
			return m.keySet().equals(Set.of("t", "id", "target", "hack", "p", "r", "secs")) && m.get("target").isJsonNull()
				&& ActWire.position(m.get("p")) != null && whole(m.get("r"), 1, 150) && whole(m.get("secs"), timed ? 1 : 0, 600);
		}

		if (hack.target() == RemoteHack.Target.VEHICLE) {
			return m.keySet().equals(Set.of("t", "id", "target", "hack", "secs")) && devId(m.get("target")) && whole(m.get("secs"), 1, 600);
		}

		return m.keySet().equals(base) && devId(m.get("target"));
	}

	private static boolean whole(final JsonElement e, final long low, final long high) {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber() || !p.getAsString().matches("-?\\d+")) {
			return false;
		}

		long v = p.getAsLong();
		return v >= low && v <= high;
	}

	private static boolean devId(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isString() && p.getAsString().matches("\\d+") && ActWire.id(e) != 0L;
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
