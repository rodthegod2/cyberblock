package dev.cyberblock.client.hack.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Net Map's state from `cyberblock:ui netmap` (RemoteActions.netMap), and the host choice the screens share. */
class NetStateTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
	private static final String STATE = "{\"hosts\":["
		+ "{\"id\":\"" + A + "\",\"name\":\"CbHost1\",\"p\":[0,64,0],\"district\":\"Watson\",\"active\":true,\"blackout\":75},"
		+ "{\"id\":\"" + B + "\",\"name\":\"CbHost2\",\"p\":[300,64,0],\"district\":\"\",\"active\":false,\"blackout\":0},"
		+ "{\"id\":\"bad\",\"name\":\"x\",\"p\":[0,0,0],\"active\":true}],"
		+ "\"cams\":[{\"h\":\"" + A + "\",\"id\":\"11529215046068469765\",\"p\":[-3.5,67,2.5],\"yaw\":-135},{\"h\":\"" + A + "\",\"id\":\"0\",\"p\":[0,0,0],\"yaw\":0}],"
		+ "\"range\":50}";

	@Test
	void parsesHostsAndCameras() {
		NetState s = NetState.parse(JsonParser.parseString(STATE).getAsJsonObject());
		assertEquals(2, s.hosts().size(), "the bad uuid is skipped");
		assertEquals(1, s.cams().size(), "id 0 is never a device");
		assertEquals(75L, s.host(A).blackout());
		assertEquals("Watson", s.host(A).district());
		assertEquals(50.0, s.range());
		assertEquals(-135.0F, s.cams().getFirst().yaw());
		assertNull(NetState.parse(JsonParser.parseString("{\"hosts\":7}").getAsJsonObject()));
		assertEquals(0.0, NetState.parse(JsonParser.parseString("{\"hosts\":[],\"cams\":[]}").getAsJsonObject()).range());
	}

	@Test
	void theHostChoiceCyclesAndNearestIsActive() {
		NetState s = NetState.parse(JsonParser.parseString(STATE).getAsJsonObject());
		assertSame(s.host(A), s.next(null));
		assertSame(s.host(B), s.next(A));
		assertSame(s.host(A), s.next(B));
		assertNull(NetState.EMPTY.next(A));
		// B is closer to x = 290 but its Cyberpunk is off
		assertSame(s.host(A), s.nearestActive(290.0, 0.0));
		assertNull(NetState.EMPTY.nearestActive(0.0, 0.0));
	}
}
