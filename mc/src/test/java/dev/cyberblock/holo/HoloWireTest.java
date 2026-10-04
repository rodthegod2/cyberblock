package dev.cyberblock.holo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 7-T1: the Holo-Table's act and ui messages, both ways, and the vteleport / vteleported pair. */
class HoloWireTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static HoloWire.State sample(final boolean withDetail) {
		UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000a");
		HoloWire.Pin pin = new HoloWire.Pin(id, "CbGuest1", 10.25, 64, -30.5, 90.4F, true, 2, 0x33AAFF, true, false, true, true);
		HoloWire.SpawnPoint spawn = new HoloWire.SpawnPoint("landmark", "cyberblock:afterlife", "Afterlife", 1, 2, 3, "Watson", "", false,
			"cyberblock.holo.cooldown");
		HoloWire.Layer layer = new HoloWire.Layer("npcs", List.of(new HoloWire.LayerItem(32, -32, 0.5F, 0x30C0E0, "4")));
		short[] h = {64, HoloWire.UNKNOWN, 65, 66};
		byte[] c = {11, 0, 12, 13};
		List<HoloWire.Detail> detail = withDetail ? List.of(new HoloWire.Detail(-1, -1, 2, h, c)) : null;
		return new HoloWire.State(true, false, "player", true, 42, List.of(pin), List.of(spawn), List.of(layer), detail);
	}

	@Test
	void theStateRoundTrips() {
		HoloWire.State s = HoloWire.parseState(json(HoloWire.state(sample(true))));
		assertNotNull(s);
		assertTrue(s.open());
		assertTrue(s.hidden());
		assertEquals(42, s.cooldown());
		HoloWire.Pin p = s.pins().getFirst();
		assertEquals("CbGuest1", p.name());
		assertEquals(10.3, p.x(), 1e-9, "positions go with 0.1 block");
		assertEquals(90.0F, p.yaw());
		assertTrue(p.linked() && p.car() && p.downed() && p.you());
		assertEquals(0x33AAFF, p.colour());
		assertEquals(2, p.team());
		HoloWire.SpawnPoint sp = s.spawns().getFirst();
		assertEquals("cyberblock:afterlife", sp.id());
		assertFalse(sp.ok());
		assertEquals("cyberblock.holo.cooldown", sp.why());
		assertEquals("Watson", sp.district());
		assertEquals(0.5F, s.layers().getFirst().items().getFirst().v());
		HoloWire.Detail d = s.detail().getFirst();
		assertArrayEquals(new short[] {64, HoloWire.UNKNOWN, 65, 66}, d.heights());
		assertArrayEquals(new byte[] {11, 0, 12, 13}, d.colours());
		assertTrue(d.covers(0, 0));
		assertFalse(d.covers(1, 0));
		assertEquals(3, d.index(0, 0));
	}

	@Test
	void aStateWithoutDetailSaysSo() {
		HoloWire.State s = HoloWire.parseState(json(HoloWire.state(sample(false))));
		assertNotNull(s);
		assertNull(s.detail(), "the client keeps the detail it has");
	}

	@Test
	void aPinWithoutTeamColourHasNone() {
		HoloWire.Pin pin = new HoloWire.Pin(UUID.randomUUID(), "a", 0, 0, 0, 0, false, -1, -1, false, false, false, false);
		HoloWire.State s = new HoloWire.State(false, false, "player", false, 0, List.of(pin), List.of(), List.of(), null);
		assertEquals(-1, HoloWire.parseState(json(HoloWire.state(s))).pins().getFirst().colour());
	}

	@Test
	void badStatesAreDroppedWhole() {
		JsonObject good = json(HoloWire.state(sample(true)));
		JsonObject noRole = good.deepCopy();
		noRole.remove("role");
		assertNull(HoloWire.parseState(noRole));
		JsonObject badPin = good.deepCopy();
		badPin.getAsJsonArray("pins").get(0).getAsJsonObject().addProperty("id", "not-a-uuid");
		assertNull(HoloWire.parseState(badPin));
		JsonObject badKind = good.deepCopy();
		badKind.getAsJsonArray("spawns").get(0).getAsJsonObject().addProperty("kind", "moon");
		assertNull(HoloWire.parseState(badKind));
		JsonObject badDetail = good.deepCopy();
		badDetail.getAsJsonArray("detail").get(0).getAsJsonObject().addProperty("n", 3);
		assertNull(HoloWire.parseState(badDetail), "n does not match the cells");
		JsonObject badLayer = good.deepCopy();
		badLayer.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("id", "Not An Id");
		assertNull(HoloWire.parseState(badLayer));
		JsonObject farPin = good.deepCopy();
		farPin.getAsJsonArray("pins").get(0).getAsJsonObject().getAsJsonArray("p").set(0, new com.google.gson.JsonPrimitive(1e9));
		assertNull(HoloWire.parseState(farPin));
	}

	@Test
	void theRequests() {
		assertTrue(HoloWire.pollDetail(json(HoloWire.poll(true))));
		assertFalse(HoloWire.pollDetail(json(HoloWire.poll(false))));
		assertFalse(HoloWire.pollDetail(json("{}")));
		HoloWire.SpawnAsk a = HoloWire.parseSpawn(json(HoloWire.spawn(HoloRules.Kind.BEACON, "1,64,-3")));
		assertEquals(HoloRules.Kind.BEACON, a.kind());
		assertEquals("1,64,-3", a.id());
		assertNull(HoloWire.parseSpawn(json("{\"kind\":\"moon\",\"id\":\"x\"}")));
		assertNull(HoloWire.parseSpawn(json("{\"kind\":\"landmark\",\"id\":\"\"}")));
		assertNull(HoloWire.parseSpawn(json("{\"kind\":\"landmark\",\"id\":\"" + "x".repeat(65) + "\"}")));
		assertEquals(Boolean.TRUE, HoloWire.parseHide(json(HoloWire.hide(true))));
		assertNull(HoloWire.parseHide(json("{\"on\":1}")));
	}

	@Test
	void theVteleportAndItsAnswer() {
		JsonObject v = json(HoloWire.vteleport(7, 1.5, 64, -2.25, 90));
		assertEquals("vteleport", v.get("t").getAsString());
		assertEquals("holo", v.get("why").getAsString());
		assertEquals(7, v.get("id").getAsInt());
		assertEquals(-2.25, v.getAsJsonArray("p").get(2).getAsDouble());
		HoloWire.Answer ok = HoloWire.parseAnswer(json("{\"t\":\"vteleported\",\"id\":7,\"ok\":true,\"reason\":\"\",\"p\":[1,64,2]}"));
		assertTrue(ok.ok());
		assertArrayEquals(new double[] {1, 64, 2}, ok.p());
		HoloWire.Answer no = HoloWire.parseAnswer(json("{\"id\":7,\"ok\":false,\"reason\":\"blocked (parked)\"}"));
		assertFalse(no.ok());
		assertEquals("blocked (parked)", no.reason());
		assertNull(HoloWire.parseAnswer(json("{\"id\":7,\"ok\":true}")), "ok needs p");
		assertNull(HoloWire.parseAnswer(json("{\"id\":7.5,\"ok\":false}")));
		assertNull(HoloWire.parseAnswer(json("{\"id\":7,\"ok\":false,\"reason\":\"" + "r".repeat(129) + "\"}")));
	}

	@Test
	void namesAreCutByBytesNeverInsideACharacter() {
		assertEquals("abc", HoloWire.cap("abc", 64));
		assertEquals("ab", HoloWire.cap("abé", 3), "é takes two bytes");
		assertEquals("#00FF10", HoloWire.colour(0x00FF10));
	}
}
