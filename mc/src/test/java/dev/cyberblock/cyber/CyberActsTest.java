package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan task 1-C1: the cyber track's `cyberblock:act` requests, built on the client and checked on the server. */
class CyberActsTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void scanRoundTrip() {
		CyberActs.Scan scan = CyberActs.parseScan(json(CyberActs.scan(12, new Vec3(1.0, 64.5, -3.0), new Vec3(0.0, 0.0, 2.0))));
		assertNotNull(scan);
		assertEquals(12, scan.target());
		assertEquals(new Vec3(1.0, 64.5, -3.0), scan.eye());
		assertEquals(new Vec3(0.0, 0.0, 1.0), scan.look(), "the look direction is normalised");
		CyberActs.Scan none = CyberActs.parseScan(json(CyberActs.scan(null, Vec3.ZERO, new Vec3(1.0, 0.0, 0.0))));
		assertNotNull(none);
		assertNull(none.target());
	}

	@Test
	void badScansAreDropped() {
		assertNull(CyberActs.parseScan(json("{'eye':[0,0,0],'look':[0,0,0]}")), "no direction");
		assertNull(CyberActs.parseScan(json("{'eye':[0,'NaN',0],'look':[0,0,1]}")));
		assertNull(CyberActs.parseScan(json("{'eye':[4e7,0,0],'look':[0,0,1]}")), "outside the world");
		assertNull(CyberActs.parseScan(json("{'eye':[0,0],'look':[0,0,1]}")));
		assertNull(CyberActs.parseScan(json("{'eye':[0,0,0],'look':[0,'Infinity',1]}")));
		assertNull(CyberActs.parseScan(json("{'eye':[0,0,0]}")));
		assertNull(CyberActs.parseScan(json("{'target':'x','eye':[0,0,0],'look':[0,0,1]}")));
	}

	@Test
	void hackPerkWareFire() {
		assertEquals(Hack.SHORT_CIRCUIT, CyberActs.parseHack(json(CyberActs.hack(Hack.SHORT_CIRCUIT))));
		assertNull(CyberActs.parseHack(json("{'hack':'format_c'}")));
		assertEquals(Perk.IRON_SKIN, CyberActs.parsePerk(json(CyberActs.perk(Perk.IRON_SKIN))));
		assertNull(CyberActs.parsePerk(json("{}")));
		CyberActs.Ware install = CyberActs.parseWare(json(CyberActs.install(Cyberware.MANTIS_BLADES)));
		assertTrue(install.install());
		assertEquals(Cyberware.MANTIS_BLADES, install.cyberware());
		CyberActs.Ware remove = CyberActs.parseWare(json(CyberActs.remove("arms/0")));
		assertFalse(remove.install());
		assertEquals("arms/0", remove.slot());
		assertNull(CyberActs.parseWare(json("{'op':'install','id':'nope'}")));
		assertNull(CyberActs.parseWare(json("{'op':'remove','slot':''}")));
		assertNull(CyberActs.parseWare(json("{'op':'steal','slot':'arms/0'}")));
		CyberActs.Fire fire = CyberActs.parseFire(json(CyberActs.fire(CyberwareRules.Trigger.ARMS, new Vec3(1, 2, 3))));
		assertEquals(CyberwareRules.Trigger.ARMS, fire.trigger());
		assertEquals(new Vec3(1, 2, 3), fire.aim());
		assertNull(CyberActs.parseFire(json(CyberActs.fire(CyberwareRules.Trigger.OS, null))).aim());
		assertNull(CyberActs.parseFire(json("{'trigger':'legs','aim':null}")));
		assertNull(CyberActs.parseFire(json("{'trigger':'os','aim':[0,1e9,0]}")));
	}

	@Test
	void typesFitThePayloadCap() {
		for (String t : new String[] {CyberActs.SCAN, CyberActs.HACK, CyberActs.PERK, CyberActs.WARE, CyberActs.FIRE, CyberActs.SANDEVISTAN}) {
			assertTrue(t.length() <= 16, t); // megaplan section 1.3: act `t` <= 16
		}
	}
}
