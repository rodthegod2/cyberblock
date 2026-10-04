package dev.cyberblock.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.cyber.Cyberware;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Megaplan 7-N1: the npc_role, dialogue, contract and ripperdoc content parsers, role matching and dialogue picking. */
class NpcContentTest {
	static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "x");

	static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@AfterEach
	void reset() {
		NpcContent.testRoles = null;
	}

	@Test
	void aBartenderRoleParsesWithDrinksAndAHubSpot() {
		NpcRole r = NpcRole.parse(ID, json("""
			{"schema":1,"screen":"bartender","name":"Bartender","vendor_types":["Drinks"],
			 "drinks":[{"name":"Neon Fizz","price":12,"effect":"minecraft:speed","seconds":60}],
			 "hub":[{"pos":[4,65,-2],"yaw":180,"skin":"cyberblock:textures/entity/npc/bartender_f.png","slim":true}]}"""));
		assertEquals("bartender", r.screen());
		assertEquals(new NpcRole.Drink("Neon Fizz", 12, Identifier.withDefaultNamespace("speed"), 60, 0), r.drinks().getFirst());
		assertEquals(new NpcRole.HubSpot(4, 65, -2, 180F, "Bartender", "cyberblock:textures/entity/npc/bartender_f.png", true), r.hub().getFirst(),
			"a spot without a name takes the role's");
		assertEquals(5, r.healPrice(), "defaults for the prices it does not set");
		assertTrue(r.has());
	}

	@Test
	void badRolesAreSkipped() {
		String[] bad = {"{\"schema\":1,\"screen\":\"pilot\"}", "{\"schema\":1,\"screen\":\"medic\",\"heal_price\":0}",
			"{\"schema\":1,\"screen\":\"vendor\",\"shops\":[\"no_namespace\"]}",
			"{\"schema\":1,\"screen\":\"bartender\",\"drinks\":[{\"name\":\"x\",\"effect\":\"minecraft:speed\",\"seconds\":9999}]}",
			"{\"schema\":1,\"screen\":\"fixer\",\"hub\":[{\"pos\":[0,0]}]}"};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> NpcRole.parse(ID, json(s)), s);
		}
	}

	@Test
	void figuresMatchByVendorTypeOrFactionIgnoringCaseAndPunctuation() {
		NpcRole doc = NpcRole.parse(ID, json("{\"schema\":1,\"screen\":\"ripperdoc\",\"vendor_types\":[\"RipperDoc\"]}"));
		NpcRole fixer = NpcRole.parse(ID, json("{\"schema\":1,\"screen\":\"fixer\",\"factions\":[\"Tyger Claws\"]}"));
		assertTrue(doc.matches("role:RipperDoc"));
		assertTrue(doc.matches("role:ripper_doc"));
		assertFalse(doc.matches("faction:RipperDoc"), "a vendor type is not a faction");
		assertTrue(fixer.matches("faction:TygerClaws"));
		assertFalse(fixer.matches(""));
		assertFalse(fixer.matches("vehicle:sedan"));
		NpcContent.testRoles = Map.of(Identifier.fromNamespaceAndPath("cyberblock", "doc"), doc, Identifier.fromNamespaceAndPath("cyberblock", "fixer"), fixer);
		assertEquals("ripperdoc", NpcContent.roleFor("role:RipperDoc").getValue().screen());
		assertNull(NpcContent.roleFor("role:Clothes"));
	}

	@Test
	void dialogueFallsBackFromAVariantAndAddsUpAcrossFiles() {
		Dialogue bar = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"bartender\",\"lines\":{\"greeting\":[\"a\",\"b\"],\"rumour\":[\"r\"]}}"));
		Dialogue hub = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"bartender\",\"lines\":{\"greeting.hub\":[\"h\"]}}"));
		Dialogue any = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"any\",\"lines\":{\"poor\":[\"p\"]}}"));
		List<Dialogue> all = List.of(bar, hub, any);
		assertEquals(List.of("h"), Dialogue.lines(all, "bartender", "greeting.hub"));
		assertEquals(List.of("a", "b"), Dialogue.lines(all, "bartender", "greeting.night"), "no variant: the plain context");
		assertEquals(List.of("p"), Dialogue.lines(all, "bartender", "poor"), "role any is everyone's");
		assertEquals(List.of(), Dialogue.lines(all, "medic", "greeting"));
		assertEquals("", Dialogue.pick(all, "medic", "greeting", new Random(1)), "no content, no line");
		assertTrue(List.of("a", "b").contains(Dialogue.pick(all, "bartender", "greeting", new Random(7))));
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"bartender\",\"lines\":{\"Greeting\":[\"x\"]}}")));
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"bartender\",\"lines\":{\"greeting\":[]}}")));
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"pilot\",\"lines\":{}}")));
	}

	@Test
	void contractsOfEachType() {
		Contract deliver = Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"type\":\"deliver\",\"target\":\"minecraft:iron_ingot\",\"count\":5,\"reward\":80}"));
		assertEquals(Contract.Type.DELIVER, deliver.type());
		assertEquals(5, deliver.count());
		Contract defeat = Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"type\":\"defeat\",\"target\":\"Maelstrom\",\"count\":3,\"reward\":200,"
			+ "\"reward_item\":{\"item\":\"minecraft:emerald\",\"count\":2}}"));
		assertEquals(Identifier.withDefaultNamespace("emerald"), defeat.rewardItem());
		assertEquals(2, defeat.rewardCount());
		Contract reach = Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"type\":\"reach\",\"pos\":[10,70,20],\"count\":9}"));
		assertEquals(1, reach.count(), "reaching is once");
		assertEquals(8.0, reach.radius());
		Contract landmark = Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"type\":\"reach\",\"target\":\"cyberblock:afterlife_door\"}"));
		assertNull(landmark.pos());
		Contract breach = Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"type\":\"breach\",\"target\":\"any\",\"count\":2}"));
		assertEquals("any", breach.target());
		String[] bad = {"{\"schema\":1,\"title\":\"T\",\"type\":\"steal\",\"target\":\"x\"}",
			"{\"schema\":1,\"title\":\"T\",\"type\":\"deliver\",\"target\":\"iron\"}",
			"{\"schema\":1,\"title\":\"T\",\"type\":\"reach\"}",
			"{\"schema\":1,\"title\":\"T\",\"type\":\"defeat\",\"target\":\"x\",\"count\":0}",
			"{\"schema\":1,\"type\":\"defeat\",\"target\":\"x\"}"};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> Contract.parse(ID, json(s)), s);
		}
	}

	@Test
	void ripperdocImplants() {
		Implant sandy = Implant.parse(ID, json("{\"schema\":1,\"implant\":\"sandevistan\",\"price\":900,\"risk\":0.6}"));
		assertEquals(Cyberware.SANDEVISTAN, sandy.cyberware());
		assertTrue(sandy.surgery());
		assertFalse(Implant.parse(ID, json("{\"schema\":1,\"implant\":\"lynx_paws\",\"price\":90}")).surgery());
		assertThrows(IllegalArgumentException.class, () -> Implant.parse(ID, json("{\"schema\":1,\"implant\":\"jetpack\",\"price\":9}")));
		assertThrows(IllegalArgumentException.class, () -> Implant.parse(ID, json("{\"schema\":1,\"implant\":\"berserk\",\"risk\":1.5}")));
	}

	record Landmark(String name, Vec3 pos) {
	}

	@Test
	void aLandmarkPositionIsReadFromAnyRecordWithAPos() {
		assertEquals(new Vec3(1, 2, 3), NpcContent.position(new Landmark("Afterlife", new Vec3(1, 2, 3))).orElseThrow());
		assertTrue(NpcContent.position("not a record").isEmpty());
	}
}
