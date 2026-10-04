package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Net Map's cameras from the hosts' `devices` lists (Phase 4 shape, shared/protocol.md). */
class NetIndexTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
	/** The protocol.md Phase 4 example's camera and door, plus broken entries. */
	private static final String LIST = "{\"t\":\"devices\",\"list\":["
		+ "{\"id\":\"11529215046068469763\",\"cls\":\"Door\",\"kind\":\"door\",\"p\":[-1238.5,64.0,811.5],\"yaw\":90.0,\"st\":0,\"fl\":16},"
		+ "{\"id\":\"11529215046068469765\",\"cls\":\"SurveillanceCamera\",\"kind\":\"camera\",\"p\":[-1233.5,67.0,812.5],\"yaw\":-135.0,\"st\":1,\"fl\":16},"
		+ "{\"id\":\"0\",\"kind\":\"camera\",\"p\":[0,0,0],\"yaw\":0},"
		+ "{\"id\":\"12\",\"kind\":\"camera\",\"p\":[0,0],\"yaw\":0},"
		+ "{\"id\":\"x\",\"kind\":\"camera\",\"p\":[0,0,0]},"
		+ "{\"id\":\"13\",\"kind\":\"camera\",\"p\":[1,2,3]}]}";

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void camerasAreKeptWhileListed() {
		NetIndex index = new NetIndex();
		index.devices(A, json(LIST), 1000L);
		assertEquals(2, index.cameras(1000L).size(), "the camera and the one without a yaw; the door and the broken ones are skipped");
		NetIndex.Cam cam = index.camera(A, Long.parseUnsignedLong("11529215046068469765"), 1000L);
		assertNotNull(cam);
		assertEquals(-135.0F, cam.yaw());
		assertEquals(67.0, cam.y());
		assertNull(index.camera(B, cam.id(), 1000L), "ids are scoped by their owner");
		assertNull(index.camera(A, Long.parseUnsignedLong("11529215046068469763"), 1000L), "a door is not a camera");
		// gone 10 s after the last list that named it
		assertNotNull(index.camera(A, 13L, 1000L + NetIndex.TTL_MS));
		assertNull(index.camera(A, 13L, 1001L + NetIndex.TTL_MS));
		assertTrue(index.cameras(1001L + NetIndex.TTL_MS).isEmpty());
	}

	@Test
	void anEndedSessionTakesItsCameras() {
		NetIndex index = new NetIndex();
		index.devices(A, json(LIST), 0L);
		index.devices(B, json(LIST), 0L);
		assertEquals(4, index.cameras(0L).size());
		index.end(A);
		assertEquals(2, index.cameras(0L).size());
		index.devices(B, json("{\"t\":\"devices\"}"), 0L);
		index.devices(B, json("{\"t\":\"devices\",\"list\":7}"), 0L);
		assertEquals(2, index.cameras(0L).size(), "a list without entries changes nothing");
	}
}
