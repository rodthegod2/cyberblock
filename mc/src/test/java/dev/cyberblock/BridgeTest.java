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
		Bridge.listen("scaninfo", got::add);
		JsonObject m = json("{'t':'scaninfo','id':3}");
		Bridge.dispatch("scaninfo", m);
		Bridge.dispatch("hackresult", json("{'t':'hackresult'}"));
		assertEquals(List.of(m), got);
	}

	@Test
	void noListenerIsNothing() {
		assertDoesNotThrow(() -> Bridge.dispatch("cam", json("{'t':'cam'}")));
	}

	/** Plan retest3 section 1.5: C and W both listen to "hello" and "state"; neither replaces the other. */
	@Test
	void twoListenersForOneTypeBothRunInRegistrationOrder() {
		List<String> got = new ArrayList<>();
		Bridge.listen("hello", m -> got.add("cyber"));
		Bridge.listen("hello", m -> got.add("ui"));
		Bridge.listen("state", m -> got.add("state"));
		Bridge.dispatch("hello", json("{'t':'hello'}"));
		assertEquals(List.of("cyber", "ui"), got);
	}

	@Test
	void aThrowingListenerIsContainedAndTheRestStillRun() {
		List<String> got = new ArrayList<>();
		Bridge.listen("key", m -> got.add("first " + m.get("k").getAsString()));
		Bridge.listen("key", m -> {
			throw new IllegalStateException("boom");
		});
		Bridge.listen("key", m -> got.add("third " + m.get("k").getAsString()));
		assertDoesNotThrow(() -> Bridge.dispatch("key", json("{'t':'key','k':'hack'}")));
		// and the next message still reaches them all
		Bridge.dispatch("key", json("{'t':'key','k':'skills'}"));
		assertEquals(List.of("first hack", "third hack", "first skills", "third skills"), got);
	}

	@Test
	void aListenerAddedWhileDispatchingRunsFromTheNextMessageOn() {
		List<String> got = new ArrayList<>();
		Bridge.listen("npcs", m -> {
			got.add("outer");
			if (got.size() == 1) {
				Bridge.listen("npcs", n -> got.add("inner"));
			}
		});
		Bridge.dispatch("npcs", json("{'t':'npcs'}"));
		assertEquals(List.of("outer"), got);
		Bridge.dispatch("npcs", json("{'t':'npcs'}"));
		assertEquals(List.of("outer", "outer", "inner"), got);
	}

	@Test
	void defaultsAreHarmless() {
		assertEquals(0, Bridge.perkRank.applyAsInt("iron_skin"));
		// plan retest2 section 1.6
		assertNull(Bridge.proxyAimedAt.at(net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0), 48.0));
		assertFalse(Bridge.hostileProxy.test(null));
		assertTrue(Bridge.installedCyberware.isEmpty());
		assertEquals(0, Bridge.alignK);
		// plan retest3 section 1.5
		assertFalse(Bridge.pingedProxy.test(null));
		assertDoesNotThrow(() -> Bridge.pingProxy.accept(null, 200));
	}
}
