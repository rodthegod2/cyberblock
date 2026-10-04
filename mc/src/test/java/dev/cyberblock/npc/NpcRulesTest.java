package dev.cyberblock.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan 7-N1: the role screens' wire, the surgery timing bar, gig rules, the medic's price and the host service requests. */
class NpcRulesTest {
	static final NpcWire.Screen SCREEN = new NpcWire.Screen(812, "shop", "Dax", "cyberblock.role.vendor", "Line", 120, "buy", List.of("buy", "sell"),
		List.of(new NpcWire.Row("b1", "minecraft:bread", 2, "", "", "", 15, "buy", true),
			new NpcWire.Row("ask", "", 0, "cyberblock.drink.neon_sour", "Neon Sour", "Sub", -1, "ask", false)), "ok");

	@Test
	void aScreenRoundTripsAndBadOnesAreDropped() {
		assertEquals(SCREEN, NpcWire.parseScreen(JsonParser.parseString(NpcWire.screen(SCREEN)).getAsJsonObject()));
		String json = NpcWire.screen(SCREEN);
		String[] bad = {json.replace("\"shop\"", "\"pilot\""), json.replace("\"buy\",\"on\"", "\"Buy!\",\"on\""), json.replace("minecraft:bread", "bread loaf"),
			json.replace("\"eddies\":120", "\"eddies\":-1"), json.replace("cyberblock.role.vendor", "Vendor Role"), "{}"};
		for (String b : bad) {
			assertNull(NpcWire.parseScreen(JsonParser.parseString(b).getAsJsonObject()), b);
		}
	}

	@Test
	void longTextIsCutToItsCapOnTheWayOut() {
		NpcWire.Screen s = new NpcWire.Screen(1, "medic", "M", "", "x".repeat(400), 0, "heal", List.of("heal"), List.of(), "");
		NpcWire.Screen back = NpcWire.parseScreen(JsonParser.parseString(NpcWire.screen(s)).getAsJsonObject());
		assertNotNull(back);
		assertEquals(NpcWire.LINE_CAP, back.line().length());
		assertEquals("éé", NpcWire.cap("ééé", 5), "whole characters only");
	}

	@Test
	void actsAreCheckedBeforeTheServerUsesThem() {
		assertEquals(new NpcWire.Act(5, "do", "b1"), NpcWire.parseAct(JsonParser.parseString(NpcWire.act(5, "do", "b1")).getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"steal\",\"arg\":\"x\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5.5,\"a\":\"do\",\"arg\":\"x\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"do\",\"arg\":\"" + "y".repeat(33) + "\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"do\",\"arg\":\"a b\"}").getAsJsonObject()));
		NpcWire.Surgery sg = new NpcWire.Surgery(5, "sandevistan", 0.41F, 0.59F, 1600, 15000);
		assertEquals(sg, NpcWire.parseSurgery(JsonParser.parseString(NpcWire.surgery(sg)).getAsJsonObject()));
		assertNull(NpcWire.parseSurgery(JsonParser.parseString(NpcWire.surgery(new NpcWire.Surgery(5, "x", 0.6F, 0.5F, 1600, 15000))).getAsJsonObject()));
	}

	@Test
	void theSurgeryBarSweepsAndTheWindowIsCentred() {
		assertEquals(0.0, Surgery.position(0, 1000), 1e-9);
		assertEquals(1.0, Surgery.position(500, 1000), 1e-9);
		assertEquals(0.5, Surgery.position(750, 1000), 1e-9);
		assertEquals(0.0, Surgery.position(1000, 1000), 1e-9, "one period: back at the start");
		assertArrayEquals(new double[] {0.41, 0.59}, Surgery.zone(18), 1e-9);
		assertArrayEquals(new double[] {0.325, 0.675}, Surgery.zone(35), 1e-9);
		assertEquals(1600, Surgery.periodMs(32), "ticks to ms");
		assertTrue(Surgery.hit(0.5, 0.45, 0.55));
		assertTrue(Surgery.hit(0.44, 0.45, 0.55), "the grace");
		assertFalse(Surgery.hit(0.40, 0.45, 0.55));
		assertEquals(900, Surgery.elapsed(1000, 2000, 100), "less one round trip");
		assertEquals(0, Surgery.elapsed(1000, 1000, 100), "never before the bar");
		assertEquals(0, Surgery.elapsed(1000, 2000, 99_999), "a silly ping counts as 1 s");
	}

	@Test
	void aFailedSurgeryNeverKills() {
		assertEquals(6.0F, Surgery.damage(6, 20.0F));
		assertEquals(2.0F, Surgery.damage(6, 3.0F), "down to half a heart, not below");
		assertEquals(0.0F, Surgery.damage(6, 1.0F));
		assertEquals(0.0F, Surgery.damage(6, 0.5F));
		assertEquals(0.0F, Surgery.damage(0, 20.0F));
	}

	@Test
	void gigObjectives() {
		Contract claws = NpcContentTest.contract("defeat_hostiles", "{\"faction\":\"TygerClaws\",\"count\":3}");
		assertTrue(GigRules.defeatCounts(claws, "faction:TygerClaws"));
		assertTrue(GigRules.defeatCounts(claws, "faction:tyger_claws"));
		assertFalse(GigRules.defeatCounts(claws, "faction:Maelstrom"));
		assertFalse(GigRules.defeatCounts(claws, ""), "a civilian is nobody's faction");
		assertFalse(GigRules.defeatCounts(claws, "role:TygerClaws"));
		Contract breach = NpcContentTest.contract("breach_device", "{\"kind\":\"terminal\",\"count\":2}");
		assertTrue(GigRules.breachCounts(breach, "terminal"));
		assertFalse(GigRules.breachCounts(breach, "camera"));
		assertFalse(GigRules.defeatCounts(breach, "faction:x"));
		Contract reach = NpcContentTest.contract("reach_landmark", "{\"landmark\":\"cyberblock:corpo_plaza\"}");
		Vec3 plaza = new Vec3(100, 64, 100);
		assertTrue(GigRules.reached(reach, plaza, false, new Vec3(104, 70, 104)));
		assertFalse(GigRules.reached(reach, plaza, false, new Vec3(105, 64, 105)), "more than 6 blocks");
		assertFalse(GigRules.reached(reach, plaza, false, new Vec3(100, 90, 100)), "not from the sky");
		assertTrue(GigRules.reached(reach, plaza, true, new Vec3(100, 90, 100)), "an approximate height is not held against the player");
		assertTrue(GigRules.reached(reach, plaza, true, new Vec3(104, 20, 104)));
		assertFalse(GigRules.reached(reach, plaza, true, new Vec3(105, 64, 105)), "approximate or not, x and z still count");
		assertFalse(GigRules.reached(reach, null, true, plaza), "no landmark, no arrival");
		assertEquals(3, GigRules.next(3, 3));
		assertEquals("2 / 3", GigRules.progress(claws, 2));
		assertEquals("", GigRules.progress(reach, 0));
		assertEquals("2 / 3  Do it.", Gigs.sub(claws, 2));
		assertEquals("Do it.", Gigs.sub(reach, 0));
	}

	@Test
	void finishedGigsAreOfferedAgainOnlyWhenRepeatableAndTimeLimitsRunOut() {
		Contract once = NpcContentTest.contract("breach_device", "{\"kind\":\"door\",\"count\":1}");
		assertTrue(GigRules.offered("cyberblock:a", once, List.of()));
		assertFalse(GigRules.offered("cyberblock:a", once, List.of("cyberblock:a")));
		Contract again = Contract.parse(NpcContentTest.ID, NpcContentTest.json("{\"schema\":1,\"title\":\"T\",\"text\":\"x\",\"type\":\"breach_device\","
			+ "\"target\":{\"kind\":\"door\",\"count\":1},\"reward\":{\"eddies\":5},\"repeatable\":true,\"time_limit\":60}"));
		assertTrue(GigRules.offered("cyberblock:a", again, List.of("cyberblock:a")));
		assertFalse(GigRules.expired(again, 1000, 1000 + 1200));
		assertTrue(GigRules.expired(again, 1000, 1000 + 1201));
		assertFalse(GigRules.expired(once, 0, Long.MAX_VALUE / 2), "no limit");
	}

	@Test
	void theMedicChargesPerMissingHeart() {
		assertEquals(0, Clinic.hearts(0.0F));
		assertEquals(1, Clinic.hearts(1.0F), "half a heart is a heart");
		assertEquals(1, Clinic.hearts(2.0F));
		assertEquals(5, Clinic.hearts(9.5F));
	}

	@Test
	void vendorRowIdsTheMechanicsCheckAndTheHostRequests() {
		assertEquals(12, Shopkeeper.cell("b12", 'b'));
		assertEquals(-1, Shopkeeper.cell("s12", 'b'));
		assertEquals(-1, Shopkeeper.cell("b", 'b'));
		assertEquals(-1, Shopkeeper.cell("b123", 'b'));
		assertTrue(Garage.CAR_UNAVAILABLE.length() <= NpcWire.SUB_CAP);
		assertTrue(Eddies.KEPT.length() <= NpcWire.SUB_CAP);
		assertEquals("Neon Sour", Bar.words("neon_sour"));
	}
}
