package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The plan retest2 section 1.2 shapes of the cyber track's new Minecraft to Cyberpunk messages. */
class CyberWireTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void cyberwareListsAllSeventeenInOrder() {
		JsonObject m = json(CyberWire.cyberware(Set.of("mantis_blades", "kiroshi_optics", "nonsense")));
		assertEquals("cyberware", m.get("t").getAsString());
		assertEquals(2, m.size());
		JsonArray list = m.getAsJsonArray("list");
		assertEquals(17, list.size());
		for (int i = 0; i < list.size(); i++) {
			JsonObject e = list.get(i).getAsJsonObject();
			Cyberware c = Cyberware.values()[i];
			assertEquals(c.id(), e.get("id").getAsString());
			assertEquals(c == Cyberware.MANTIS_BLADES || c == Cyberware.KIROSHI_OPTICS ? 1 : 0, e.get("on").getAsInt(), c.id());
			assertEquals(2, e.size());
		}

		assertTrue(CyberWire.cyberware(Set.of()).startsWith("{\"t\":\"cyberware\",\"list\":[{\"id\":\"mantis_blades\",\"on\":0},"));
	}

	@Test
	void cyberfxSecondsAreClampedToThirty() {
		assertEquals("{\"t\":\"cyberfx\",\"id\":\"sandevistan\",\"seconds\":6.0}", CyberWire.cyberfx("sandevistan", 6.0));
		assertEquals("{\"t\":\"cyberfx\",\"id\":\"kerenzikov\",\"seconds\":2.0}", CyberWire.cyberfx("kerenzikov", 2.0));
		assertEquals("{\"t\":\"cyberfx\",\"id\":\"berserk\",\"seconds\":30.0}", CyberWire.cyberfx("berserk", 99.0));
		assertEquals("{\"t\":\"cyberfx\",\"id\":\"berserk\",\"seconds\":0.0}", CyberWire.cyberfx("berserk", -1.0));
	}

	@Test
	void bindsCarriesAllFourKeys() {
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":71,\"skills\":75,\"cyberware\":85,\"activate\":89}}", CyberWire.binds(71, 75, 85, 89));
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":76,\"skills\":79,\"cyberware\":48,\"activate\":121}}", CyberWire.binds(new int[]{76, 79, 48, 121}));
	}
}
