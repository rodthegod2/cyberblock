package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BridgeTest {
	@AfterEach
	void forgetListeners() {
		Bridge.listeners.clear();
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void theListenerForTheTypeGetsTheMessage() {
		List<JsonObject> got = new ArrayList<>();
		Bridge.listeners.put("scaninfo", got::add);
		JsonObject m = json("{'t':'scaninfo','id':3}");
		Bridge.dispatch("scaninfo", m);
		Bridge.dispatch("hackresult", json("{'t':'hackresult'}"));
		assertEquals(List.of(m), got);
	}

	@Test
	void noListenerIsNothing() {
		assertDoesNotThrow(() -> Bridge.dispatch("cam", json("{'t':'cam'}")));
	}

	@Test
	void aThrowingListenerIsContained() {
		Bridge.listeners.put("key", m -> {
			throw new IllegalStateException("boom");
		});
		assertDoesNotThrow(() -> Bridge.dispatch("key", json("{'t':'key','k':'hack'}")));
		// and the next message still reaches it
		List<String> got = new ArrayList<>();
		Bridge.listeners.put("key", m -> got.add(m.get("k").getAsString()));
		Bridge.dispatch("key", json("{'t':'key','k':'skills'}"));
		assertEquals(List.of("skills"), got);
	}

	@Test
	void defaultsAreHarmless() {
		assertEquals(0, Bridge.perkRank.applyAsInt("iron_skin"));
		// plan retest2 section 1.6
		assertNull(Bridge.proxyAimedAt.at(net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), 48.0));
		assertFalse(Bridge.hostileProxy.test(null));
		assertTrue(Bridge.installedCyberware.isEmpty());
		assertEquals(0, Bridge.alignK);
	}
}
