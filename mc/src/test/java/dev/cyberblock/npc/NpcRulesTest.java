package dev.cyberblock.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan 7-N1: the role screens' wire, the surgery timing bar, gig rules, the medic's price and the mechanic's runtime check. */
class NpcRulesTest {
	@Test
	void aScreenRoundTripsAndBadOnesAreDropped() {
		NpcWire.Screen s = new NpcWire.Screen(812, "vendor", "Vendor", "Line", 120, "buy", List.of("buy", "sell"),
			List.of(new NpcWire.Row("b0.1", "minecraft:bread", 2, "", "", 15, "buy", true), new NpcWire.Row("ask", "", 0, "Text", "Sub", -1, "ask", false)), "ok");
		assertEquals(s, NpcWire.parseScreen(JsonParser.parseString(NpcWire.screen(s)).getAsJsonObject()));
		String[] bad = {NpcWire.screen(s).replace("\"vendor\"", "\"pilot\""), NpcWire.screen(s).replace("\"buy\",\"on\"", "\"Buy!\",\"on\""),
			NpcWire.screen(s).replace("minecraft:bread", "bread loaf"), NpcWire.screen(s).replace("\"eddies\":120", "\"eddies\":-1"), "{}"};
		for (String b : bad) {
			assertNull(NpcWire.parseScreen(JsonParser.parseString(b).getAsJsonObject()), b);
		}
	}

	@Test
	void longTextIsCutToItsCapOnTheWayOut() {
		String line = "x".repeat(400);
		NpcWire.Screen s = new NpcWire.Screen(1, "medic", "M", line, 0, "heal", List.of("heal"), List.of(), "");
		NpcWire.Screen back = NpcWire.parseScreen(JsonParser.parseString(NpcWire.screen(s)).getAsJsonObject());
		assertNotNull(back);
		assertEquals(NpcWire.LINE_CAP, back.line().length());
		assertEquals("éé", NpcWire.cap("ééé", 5), "whole characters only");
	}

	@Test
	void actsAreCheckedBeforeTheServerUsesThem() {
		assertEquals(new NpcWire.Act(5, "do", "b0.1"), NpcWire.parseAct(JsonParser.parseString(NpcWire.act(5, "do", "b0.1")).getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"steal\",\"arg\":\"x\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5.5,\"a\":\"do\",\"arg\":\"x\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"do\",\"arg\":\"" + "y".repeat(33) + "\"}").getAsJsonObject()));
		assertNull(NpcWire.parseAct(JsonParser.parseString("{\"e\":5,\"a\":\"do\",\"arg\":\"a b\"}").getAsJsonObject()));
		NpcWire.Surgery sg = new NpcWire.Surgery(5, "sandevistan", 0.4F, 0.5F, 1260, 15000);
		assertEquals(sg, NpcWire.parseSurgery(JsonParser.parseString(NpcWire.surgery(sg)).getAsJsonObject()));
		assertNull(NpcWire.parseSurgery(JsonParser.parseString(NpcWire.surgery(new NpcWire.Surgery(5, "x", 0.6F, 0.5F, 1260, 15000))).getAsJsonObject()));
	}

	@Test
	void theSurgeryBarSweepsAndTheRiskierTheHarder() {
		assertEquals(0.0, Surgery.position(0, 1000), 1e-9);
		assertEquals(1.0, Surgery.position(500, 1000), 1e-9);
		assertEquals(0.5, Surgery.position(750, 1000), 1e-9);
		assertEquals(0.0, Surgery.position(1000, 1000), 1e-9, "one period: back at the start");
		assertTrue(Surgery.period(1.0) < Surgery.period(0.1));
		assertTrue(Surgery.width(1.0) < Surgery.width(0.1));
		Random r = new Random(3);
		for (int i = 0; i < 200; i++) {
			double risk = r.nextDouble();
			double[] z = Surgery.zone(risk, r);
			assertTrue(z[0] >= 0.15 && z[1] <= 0.85 + 1e-9, "inside the bar's middle");
			assertEquals(Surgery.width(risk), z[1] - z[0], 1e-9);
		}

		assertTrue(Surgery.hit(0.5, 0.45, 0.55));
		assertTrue(Surgery.hit(0.44, 0.45, 0.55), "the grace");
		assertFalse(Surgery.hit(0.40, 0.45, 0.55));
		assertEquals(900, Surgery.elapsed(1000, 2000, 100), "less one round trip");
		assertEquals(0, Surgery.elapsed(1000, 1000, 100), "never before the bar");
		assertEquals(0, Surgery.elapsed(1000, 2000, 99_999), "a silly ping counts as 1 s");
	}

	@Test
	void aFailedSurgeryNeverKills() {
		assertEquals(4.0F, Surgery.damage(0.0, 20.0F));
		assertEquals(12.0F, Surgery.damage(1.0, 20.0F));
		assertEquals(2.0F, Surgery.damage(1.0, 3.0F), "down to 1, not below");
		assertEquals(0.0F, Surgery.damage(1.0, 1.0F));
		assertEquals(0.0F, Surgery.damage(1.0, 0.5F));
	}

	static Contract contract(final String json) {
		return Contract.parse(NpcContentTest.ID, NpcContentTest.json(json));
	}

	@Test
	void gigObjectives() {
		Contract claws = contract("{\"schema\":1,\"title\":\"T\",\"type\":\"defeat\",\"target\":\"Tyger Claws\",\"count\":3}");
		assertTrue(GigRules.defeatCounts(claws, "faction:TygerClaws"));
		assertFalse(GigRules.defeatCounts(claws, "faction:Maelstrom"));
		assertFalse(GigRules.defeatCounts(claws, ""), "a civilian is nobody's faction");
		assertFalse(GigRules.defeatCounts(claws, "role:TygerClaws"));
		Contract any = contract("{\"schema\":1,\"title\":\"T\",\"type\":\"defeat\",\"target\":\"any\",\"count\":3}");
		assertTrue(GigRules.defeatCounts(any, ""));
		Contract breach = contract("{\"schema\":1,\"title\":\"T\",\"type\":\"breach\",\"target\":\"terminal\",\"count\":2}");
		assertTrue(GigRules.breachCounts(breach, "terminal"));
		assertFalse(GigRules.breachCounts(breach, "camera"));
		assertFalse(GigRules.defeatCounts(breach, "faction:x"));
		Contract reach = contract("{\"schema\":1,\"title\":\"T\",\"type\":\"reach\",\"pos\":[100,64,100],\"radius\":5}");
		assertTrue(GigRules.reached(reach, new Vec3(100, 64, 100), new Vec3(103, 70, 103)));
		assertFalse(GigRules.reached(reach, new Vec3(100, 64, 100), new Vec3(104, 64, 104)));
		assertFalse(GigRules.reached(reach, new Vec3(100, 64, 100), new Vec3(100, 90, 100)), "not from the sky");
		assertFalse(GigRules.reached(reach, null, new Vec3(100, 64, 100)), "no place, no arrival");
		assertEquals(3, GigRules.next(3, 3));
		assertEquals("2 / 3", GigRules.progressLine(claws, 2));
		assertEquals("", GigRules.progressLine(reach, 0));
		assertEquals(new Vec3(100, 64, 100), Gigs.target(reach));
	}

	@Test
	void theMedicChargesPerMissingHeart() {
		assertEquals(0, Clinic.hearts(0.0F));
		assertEquals(1, Clinic.hearts(1.0F), "half a heart is a heart");
		assertEquals(1, Clinic.hearts(2.0F));
		assertEquals(5, Clinic.hearts(9.5F));
	}

	@Test
	void vendorRowIdsAndTheMechanicsRuntimeCheck() {
		assertArrayEquals(new int[] {1, 12}, Shopkeeper.cell("b1.12", 'b'));
		assertNull(Shopkeeper.cell("s1.12", 'b'));
		assertNull(Shopkeeper.cell("b1", 'b'));
		assertNull(Shopkeeper.cell("b123.1", 'b'));
		assertEquals("not_host", Garage.vehicleCheck(false, false, false));
		assertEquals("not_active", Garage.vehicleCheck(true, false, true));
		assertEquals("no_vehicle", Garage.vehicleCheck(true, true, false));
		assertEquals("", Garage.vehicleCheck(true, true, true));
		assertEquals("{\"t\":\"repair\",\"what\":\"vehicle\"}", Garage.repairMessage());
	}

	@Test
	void hubSpotKeysAreStablePerRoleAndIndex() {
		assertEquals("cyberblock:bartender#0", HubNpcs.spotKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("cyberblock", "bartender"), 0));
	}
}
