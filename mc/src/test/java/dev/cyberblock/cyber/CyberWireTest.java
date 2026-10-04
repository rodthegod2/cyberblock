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
	void cyberwareListsEveryImplantInOrder() {
		JsonObject m = json(CyberWire.cyberware(Set.of("mantis_blades", "kiroshi_optics", "nonsense")));
		assertEquals("cyberware", m.get("t").getAsString());
		assertEquals(2, m.size());
		JsonArray list = m.getAsJsonArray("list");
		// the 17 of plan retest2 plus megaplan section 1.17's firewall (Phase 6), within the wire's 32
		assertEquals(18, list.size());
		assertTrue(list.size() <= CyberwareRules.MAX_IDS);
		assertEquals("firewall", list.get(17).getAsJsonObject().get("id").getAsString());
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
	void bindsCarriesAllSixKeys() {
		assertEquals(java.util.List.of("hack", "skills", "cyberware", "activate", "arms", "camo"), CyberWire.BIND_NAMES);
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":71,\"skills\":75,\"cyberware\":85,\"activate\":89,\"arms\":76,\"camo\":79}}",
			CyberWire.binds(71, 75, 85, 89, 76, 79));
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":48,\"skills\":75,\"cyberware\":85,\"activate\":121,\"arms\":122,\"camo\":79}}",
			CyberWire.binds(new int[]{48, 75, 85, 121, 122, 79}));
	}
}
