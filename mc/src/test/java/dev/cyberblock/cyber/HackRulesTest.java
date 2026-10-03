package dev.cyberblock.cyber;

import static dev.cyberblock.cyber.HackRules.Outcome.NEED_DEVICE;
import static dev.cyberblock.cyber.HackRules.Outcome.NEED_NPC;
import static dev.cyberblock.cyber.HackRules.Outcome.NO_DECK;
import static dev.cyberblock.cyber.HackRules.Outcome.NO_RAM;
import static dev.cyberblock.cyber.HackRules.Outcome.NO_TARGET;
import static dev.cyberblock.cyber.HackRules.Outcome.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HackRulesTest {
	private static HackRules.Outcome npc(final Hack hack, final int ram) {
		return HackRules.check(true, true, false, true, hack, ram, Ram.cost(hack, false));
	}

	@Test
	void aPersonWithEnoughRam() {
		assertEquals(OK, npc(Hack.SHORT_CIRCUIT, 3));
		assertEquals(OK, npc(Hack.CYBERPSYCHOSIS, 12));
	}

	@Test
	void notEnoughRam() {
		assertEquals(NO_RAM, npc(Hack.SHORT_CIRCUIT, 2));
		assertEquals(NO_RAM, npc(Hack.CYBERPSYCHOSIS, 7));
		// cheap_hacks makes 2 enough for a Redstone Charge
		assertEquals(OK, HackRules.check(true, true, false, true, Hack.SHORT_CIRCUIT, 2, Ram.cost(Hack.SHORT_CIRCUIT, true)));
	}

	@Test
	void theRulesInTheirOrder() {
		// no deck beats everything, then no target, then the target kind, then RAM
		assertEquals(NO_DECK, HackRules.check(false, false, false, false, Hack.PING, 0, 1));
		assertEquals(NO_TARGET, HackRules.check(true, false, false, false, Hack.PING, 0, 1));
		assertEquals(NEED_NPC, HackRules.check(true, true, true, false, Hack.PING, 0, 1));
		assertEquals(NO_RAM, HackRules.check(true, true, false, true, Hack.PING, 0, 1));
	}

	@Test
	void theLeverNeedsADevice() {
		assertEquals(OK, HackRules.check(true, true, true, false, Hack.DEVICE, 1, 1));
		assertEquals(NEED_DEVICE, HackRules.check(true, true, false, true, Hack.DEVICE, 1, 1));
		assertEquals(NEED_DEVICE, HackRules.check(true, true, false, false, Hack.DEVICE, 1, 1));
	}

	@Test
	void theOthersNeedAProxy() {
		for (Hack h : Hack.values()) {
			if (!h.needsDevice()) {
				assertEquals(NEED_NPC, HackRules.check(true, true, true, false, h, 20, h.ram()), h + " on a device");
				assertEquals(NEED_NPC, HackRules.check(true, true, false, false, h, 20, h.ram()), h + " without a proxy");
				assertEquals(OK, HackRules.check(true, true, false, true, h, 20, h.ram()), h.name());
			}
		}
	}

	@Test
	void hackJson() {
		assertEquals("{\"t\":\"hack\",\"id\":12,\"target\":\"9876543210\",\"hack\":\"short_circuit\",\"chain\":false}",
			CyberWire.hack(12, 9876543210L, Hack.SHORT_CIRCUIT, false));
		assertEquals("{\"t\":\"hack\",\"id\":0,\"target\":\"18446744073709551615\",\"hack\":\"device\",\"chain\":true}",
			CyberWire.hack(0, -1L, Hack.DEVICE, true));
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void hackResultParse() {
		HackResult ok = HackResult.parse(json("{\"t\":\"hackresult\",\"id\":4,\"ok\":true,\"reason\":\"\"}"));
		assertNotNull(ok);
		assertEquals(4, ok.id());
		assertTrue(ok.ok());
		HackResult no = HackResult.parse(json("{\"t\":\"hackresult\",\"id\":5,\"ok\":false,\"reason\":\"target out of range\"}"));
		assertNotNull(no);
		assertFalse(no.ok());
		assertEquals("target out of range", no.reason());
		assertNotNull(HackResult.parse(json("{\"id\":6,\"ok\":true}")));
		assertNull(HackResult.parse(json("{\"id\":-1,\"ok\":true,\"reason\":\"\"}")));
		assertNull(HackResult.parse(json("{\"ok\":true}")));
		assertNull(HackResult.parse(json("{\"id\":1,\"ok\":\"true\"}")));
		assertNull(HackResult.parse(json("{\"id\":1}")));
		assertNull(HackResult.parse(json("{\"id\":1,\"ok\":false,\"reason\":\"" + "r".repeat(65) + "\"}")));
	}
}
