package dev.cyberblock.mode.van;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.cyber.Hack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VanWireTest {
	private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void opsRoundTrip() {
		assertEquals(new VanWire.LinkHost(HOST), VanWire.parseOp(json(VanWire.linkHost(HOST))));
		assertEquals(new VanWire.Launch(), VanWire.parseOp(json(VanWire.launch())));
		assertEquals(new VanWire.Pilot(false), VanWire.parseOp(json(VanWire.pilot(false))));
		assertEquals(new VanWire.Dock(), VanWire.parseOp(json(VanWire.dock())));
		assertEquals(new VanWire.Screen(true), VanWire.parseOp(json(VanWire.screen(true))));
	}

	@Test
	void badOpsAreDropped() {
		assertNull(VanWire.parseOp(json("{}")));
		assertNull(VanWire.parseOp(json("{\"op\":\"fly\"}")));
		assertNull(VanWire.parseOp(json("{\"op\":\"host\",\"host\":\"nope\"}")));
		assertNull(VanWire.parseOp(json("{\"op\":\"pilot\",\"on\":\"yes\"}")));
		assertNull(VanWire.parseOp(json("{\"op\":\"screen\"}")));
	}

	@Test
	void requestsRoundTrip() {
		assertEquals(new VanWire.FigureHack(42, Hack.PING), VanWire.parseRequest(json(VanWire.figureHack(42, Hack.PING))));
		assertEquals(new VanWire.DeviceRequest(HOST, -2L, "open"), VanWire.parseRequest(json(VanWire.deviceAct(HOST, -2L, "open"))));
		assertEquals(new VanWire.DeviceRequest(HOST, 7L, null), VanWire.parseRequest(json(VanWire.deviceChip(HOST, 7L))));
	}

	@Test
	void badRequestsAreDropped() {
		// the lever is a device chip; a figure needs a person's chip
		assertNull(VanWire.parseRequest(json("{\"eid\":3,\"hack\":\"device\"}")));
		assertNull(VanWire.parseRequest(json("{\"eid\":-1,\"hack\":\"ping\"}")));
		assertNull(VanWire.parseRequest(json("{\"eid\":3,\"hack\":\"nuke\"}")));
		assertNull(VanWire.parseRequest(json("{\"eid\":3,\"hack\":\"ping\",\"dev\":\"5\"}")));
		// a device needs a host, an id and exactly one of act or the lever
		assertNull(VanWire.parseRequest(json("{\"dev\":\"5\",\"act\":\"open\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"0\",\"act\":\"open\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":5,\"act\":\"open\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"5\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"5\",\"act\":\"open\",\"hack\":\"device\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"5\",\"hack\":\"ping\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"5\",\"act\":\"Open Sesame\"}")));
		assertNull(VanWire.parseRequest(json("{\"host\":\"" + HOST + "\",\"dev\":\"99999999999999999999999\",\"act\":\"open\"}")));
	}

	@Test
	void stateRoundTrip() {
		UUID other = UUID.randomUUID();
		VanWire.State s = new VanWire.State(true, 12, HOST, new Vec3(1.25, 64, -3.5),
			List.of(new VanWire.Host(HOST, "CbHost1", new Vec3(1.25, 64, -3.5), true), new VanWire.Host(other, "CbHost2", new Vec3(400, 70, 0), false)),
			new VanWire.Drone(99, 0.75F, 12.5, true), 0.75F,
			List.of(new VanWire.Target(VanRules.Kind.PERSON, 5, null, 0L, "Ganger", new Vec3(3, 64, 4), true, "", VanRules.Seen.SCREEN),
				new VanWire.Target(VanRules.Kind.DEVICE, -1, other, -5L, "Door", new Vec3(410, 70, 2), false, "door", VanRules.Seen.DRONE)));
		VanWire.State back = VanWire.parseState(json(VanWire.state(s)));
		assertNotNull(back);
		assertEquals(s, back);
	}

	@Test
	void stateWithoutHostOrDrone() {
		VanWire.State s = new VanWire.State(false, -1, null, null, List.of(), null, 1.0F, List.of());
		assertEquals(s, VanWire.parseState(json(VanWire.state(s))));
	}

	@Test
	void stateCapsTargetsAndNamesAndSkipsBadOnes() {
		List<VanWire.Target> many = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			many.add(new VanWire.Target(VanRules.Kind.CAR, i, null, 0L, "x".repeat(100), Vec3.ZERO, false, "", VanRules.Seen.SCREEN));
		}

		VanWire.State back = VanWire.parseState(json(VanWire.state(new VanWire.State(false, 1, null, null, List.of(), null, 0.0F, many))));
		assertNotNull(back);
		assertEquals(VanRules.MAX_TARGETS, back.targets().size());
		assertEquals(VanWire.NAME_MAX, back.targets().getFirst().name().length());
		JsonObject m = json(VanWire.state(new VanWire.State(false, 1, null, null, List.of(), null, 0.0F, List.of())));
		m.getAsJsonArray("targets").add(json("{\"k\":\"tank\",\"eid\":1,\"name\":\"\",\"p\":[0,0,0],\"hostile\":false,\"seen\":\"screen\"}"));
		m.getAsJsonArray("targets").add(json("{\"k\":\"person\",\"eid\":1,\"name\":\"\",\"p\":[0,1e9,0],\"hostile\":false,\"seen\":\"screen\"}"));
		VanWire.State skipped = VanWire.parseState(m);
		assertNotNull(skipped);
		assertTrue(skipped.targets().isEmpty());
	}

	@Test
	void brokenStateIsNull() {
		assertNull(VanWire.parseState(json("{\"open\":true}")));
	}

	@Test
	void feedIsTheNetMapsFrameShape() {
		JsonObject m = json(VanWire.feed(HOST, new byte[] {1, 2, 3}));
		assertEquals(HOST.toString(), m.get("host").getAsString());
		assertInstanceOf(String.class, m.get("px").getAsString());
	}
}
