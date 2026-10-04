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

/** Megaplan 7-N1: 7A's npc_role, dialogue, contract and ripperdoc files read by this package, role matching and dialogue picking. */
class NpcContentTest {
	static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "x");
	static final Identifier BARTENDER = Identifier.fromNamespaceAndPath("cyberblock", "bartender");

	static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@AfterEach
	void reset() {
		NpcContent.testRoles = null;
		NpcContent.testCatalogues = null;
	}

	static final String BAR = """
		{"schema":1,"title_key":"cyberblock.role.bartender","screen":"bartender","services":["rumours","drinks"],"tags":["Drinks","bartender"],
		 "dialogue":"cyberblock:bartender","drinks":[{"key":"neon_sour","price":12,"effect":"minecraft:speed","seconds":90},
		 {"key":"static_shot","price":15,"effect":"minecraft:haste","amplifier":1,"seconds":60}],"hub":{"area":"bar"}}""";
	static final String VENDOR = """
		{"schema":1,"title_key":"cyberblock.role.vendor","screen":"shop","services":["buy","sell"],"tags":["Gunsmith","Clothes","Food"],
		 "dialogue":"cyberblock:vendor","shop":"cyberblock:afterlife_gear","shop_by_tag":{"Gunsmith":"cyberblock:afterlife_arms"},"hub":{"area":"shops"}}""";

	@Test
	void sevenAsRolesParse() {
		NpcRole bar = NpcRole.parse(ID, json(BAR));
		assertEquals("bartender", bar.screen());
		assertEquals(new NpcRole.Drink("static_shot", 15, Identifier.withDefaultNamespace("haste"), 1, 60), bar.drinks().get(1));
		assertTrue(bar.offers("rumours") && !bar.offers("buy"));
		assertEquals(BARTENDER, bar.dialogue());
		assertEquals("bar", bar.hubArea());
		NpcRole medic = NpcRole.parse(ID, json("""
			{"schema":1,"title_key":"cyberblock.role.medic","screen":"medic","services":["heal"],"tags":["Medical","medic"],"dialogue":"cyberblock:medic",
			 "prices":{"heal_per_heart":6}}"""));
		assertEquals(6, medic.healPerHeart());
		assertEquals(40, medic.repairGear(), "a price it does not set has its default");
	}

	@Test
	void badRolesAreSkipped() {
		String[] bad = {BAR.replace("\"bartender\",\"services\"", "\"pilot\",\"services\""), BAR.replace("\"rumours\",\"drinks\"", "\"juggling\""),
			BAR.replace("\"tags\":[\"Drinks\",\"bartender\"]", "\"tags\":[]"), BAR.replace("cyberblock.role.bartender", "Bartender!"),
			BAR.replace("\"seconds\":90", "\"seconds\":9000"), BAR.replace("\"dialogue\":\"cyberblock:bartender\"", "\"dialogue\":\"bartender\"")};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> NpcRole.parse(ID, json(s)), s);
		}
	}

	@Test
	void figuresMatchByTheirVendorTagAndPickTheTagsShop() {
		NpcRole vendor = NpcRole.parse(ID, json(VENDOR));
		assertTrue(vendor.matches("role:Gunsmith"));
		assertTrue(vendor.matches("role:gunsmith"));
		assertFalse(vendor.matches("faction:Gunsmith"), "only role tags");
		assertFalse(vendor.matches("role:RipperDoc"));
		assertFalse(vendor.matches(""));
		assertEquals(Identifier.fromNamespaceAndPath("cyberblock", "afterlife_arms"), vendor.shopFor("GUNSMITH"));
		assertEquals(Identifier.fromNamespaceAndPath("cyberblock", "afterlife_gear"), vendor.shopFor("Food"), "no tag entry: the role's shop");
		assertEquals("Gunsmith", NpcRole.tag("role:Gunsmith"));
		assertEquals("", NpcRole.tag("faction:Valentinos"));
		NpcContent.testRoles = Map.of(Identifier.fromNamespaceAndPath("cyberblock", "vendor"), vendor, BARTENDER, NpcRole.parse(ID, json(BAR)));
		assertEquals("bartender", NpcContent.roleFor("role:Drinks").getValue().screen());
		assertNull(NpcContent.roleFor("role:RipperDoc"));
	}

	@Test
	void dialogueComesFromTheRolesFileAndFilesNamingTheRole() {
		Dialogue own = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"cyberblock:bartender\",\"lines\":{\"greeting\":[\"Evening, {player}.\"],\"rumour\":[\"r\"]}}"));
		Dialogue extra = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"cyberblock:bartender\",\"lines\":{\"rumour\":[\"s\"]}}"));
		Dialogue other = Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"cyberblock:medic\",\"lines\":{\"greeting\":[\"m\"]}}"));
		Map<Identifier, Dialogue> all = Map.of(BARTENDER, own, Identifier.fromNamespaceAndPath("pack", "more_rumours"), extra,
			Identifier.fromNamespaceAndPath("cyberblock", "medic"), other);
		assertEquals(List.of("r", "s"), Dialogue.lines(all, BARTENDER, BARTENDER, "rumour").stream().sorted().toList());
		assertEquals(List.of(), Dialogue.lines(all, BARTENDER, BARTENDER, "healed"));
		assertEquals("Evening, Vee.", Dialogue.pick(all, BARTENDER, BARTENDER, "greeting", Map.of("player", "Vee"), new Random(1)));
		assertEquals("", Dialogue.pick(all, BARTENDER, BARTENDER, "bark", Map.of(), new Random(1)), "no content, no line");
		assertEquals("That is {price} eddies.", Dialogue.fill("That is {price} eddies.", Map.of("player", "x")), "an unknown placeholder stays");
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"cyberblock:x\",\"lines\":{\"Greeting\":[\"x\"]}}")));
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"cyberblock:x\",\"lines\":{\"greeting\":[]}}")));
		assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"schema\":1,\"role\":\"bartender\",\"lines\":{}}")));
	}

	static Contract contract(final String type, final String target) {
		return Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"text\":\"Do it.\",\"type\":\"" + type + "\",\"target\":" + target
			+ ",\"reward\":{\"eddies\":100}}"));
	}

	@Test
	void contractsOfEachType() {
		Contract deliver = contract("deliver_item", "{\"item\":\"minecraft:copper_ingot\",\"count\":32}");
		assertEquals(Identifier.withDefaultNamespace("copper_ingot"), deliver.item());
		assertEquals(32, deliver.count());
		assertFalse(deliver.repeatable(), "absent: false");
		Contract defeat = Contract.parse(ID, json("""
			{"schema":1,"title":"Claw Trimming","text":"t","done_text":"d","type":"defeat_hostiles","target":{"faction":"TygerClaws","count":6},
			 "reward":{"eddies":260,"items":[{"item":"cyberblock:ram_shard","count":1}]},"difficulty":2,"repeatable":true,"time_limit":600}"""));
		assertEquals("TygerClaws", defeat.faction());
		assertEquals(List.of(new Contract.Reward(Identifier.fromNamespaceAndPath("cyberblock", "ram_shard"), 1)), defeat.items());
		assertEquals(600, defeat.timeLimit());
		assertTrue(defeat.repeatable());
		Contract reach = contract("reach_landmark", "{\"landmark\":\"cyberblock:corpo_plaza\"}");
		assertEquals(Identifier.fromNamespaceAndPath("cyberblock", "corpo_plaza"), reach.landmark());
		assertEquals(1, reach.count());
		Contract breach = contract("breach_device", "{\"kind\":\"turret\",\"count\":2}");
		assertEquals("turret", breach.kind());
		String[][] bad = {{"steal", "{}"}, {"deliver_item", "{\"item\":\"copper\",\"count\":1}"}, {"reach_landmark", "{}"},
			{"defeat_hostiles", "{\"faction\":\"Tyger Claws\",\"count\":1}"}, {"breach_device", "{\"kind\":\"toaster\",\"count\":1}"},
			{"defeat_hostiles", "{\"faction\":\"x\",\"count\":0}"}};
		for (String[] b : bad) {
			assertThrows(IllegalArgumentException.class, () -> contract(b[0], b[1]), b[0] + " " + b[1]);
		}

		assertThrows(IllegalArgumentException.class, () -> Contract.parse(ID, json("{\"schema\":1,\"title\":\"T\",\"text\":\"x\",\"type\":\"breach_device\","
			+ "\"target\":{\"kind\":\"door\",\"count\":1}}")), "a reward is required");
	}

	@Test
	void ripperdocCatalogues() {
		Catalogue c = Catalogue.parse(ID, json("""
			{"schema":1,"title_key":"cyberblock.afterlife.clinic","implants":[{"implant":"lynx_paws","price":900},
			 {"implant":"sandevistan","price":4800,"surgery":{"window":18,"period":32}}],"failure":{"damage":6,"debuff_s":30}}"""));
		assertEquals(new Catalogue.Offer(Cyberware.LYNX_PAWS, 900, 0, 0), c.offers().getFirst());
		assertTrue(c.offers().get(1).surgery());
		assertEquals(18, c.offers().get(1).window());
		assertEquals(6, c.failDamage());
		assertEquals(30, c.debuffSeconds());
		assertThrows(IllegalArgumentException.class, () -> Catalogue.parse(ID, json("{\"schema\":1,\"implants\":[{\"implant\":\"jetpack\",\"price\":9}]}")));
		assertThrows(IllegalArgumentException.class, () -> Catalogue.parse(ID, json("{\"schema\":1,\"implants\":[]}")));
		assertThrows(IllegalArgumentException.class, () -> Catalogue.parse(ID, json(
			"{\"schema\":1,\"implants\":[{\"implant\":\"berserk\",\"price\":9,\"surgery\":{\"window\":95,\"period\":30}}]}")));
	}

	@Test
	void aRoleUsesItsOwnCatalogueElseTheFirst() {
		Catalogue a = Catalogue.parse(ID, json("{\"schema\":1,\"implants\":[{\"implant\":\"lynx_paws\",\"price\":900}]}"));
		Catalogue b = Catalogue.parse(ID, json("{\"schema\":1,\"implants\":[{\"implant\":\"berserk\",\"price\":900}]}"));
		NpcContent.testCatalogues = new java.util.TreeMap<>(Map.of(Identifier.fromNamespaceAndPath("cyberblock", "a"), a,
			Identifier.fromNamespaceAndPath("cyberblock", "b"), b));
		NpcRole doc = NpcRole.parse(ID, json("{\"schema\":1,\"title_key\":\"k\",\"screen\":\"ripperdoc\",\"services\":[\"install_implant\"],"
			+ "\"tags\":[\"RipperDoc\"],\"dialogue\":\"cyberblock:ripperdoc\",\"catalogue\":\"cyberblock:b\"}"));
		assertSame(b, NpcContent.catalogue(doc));
		NpcRole none = NpcRole.parse(ID, json("{\"schema\":1,\"title_key\":\"k\",\"screen\":\"ripperdoc\",\"services\":[\"install_implant\"],"
			+ "\"tags\":[\"RipperDoc\"],\"dialogue\":\"cyberblock:ripperdoc\"}"));
		assertSame(a, NpcContent.catalogue(none));
	}

	record Landmark(String name, Vec3 pos) {
	}

	@Test
	void aLandmarkPositionIsReadFromAnyRecordWithAPos() {
		assertEquals(new Vec3(1, 2, 3), NpcContent.position(new Landmark("Afterlife", new Vec3(1, 2, 3))).orElseThrow());
		assertTrue(NpcContent.position("not a record").isEmpty());
	}

	@Test
	void hubFiguresComeFromTheLayoutInWorldCoordinates() {
		List<HubNpcs.Figure> f = HubNpcs.parse(json("""
			{"origin":[0,64,0],"figures":[{"id":"bartender","role":"cyberblock:bartender","area":"bar","pos":[21.5,1.0,4.5],"yaw":0.0,"gender":"female","name":"Juno"},
			 {"id":"vendor_arms","role":"cyberblock:vendor","area":"shops","pos":[14.5,1.0,26.5],"yaw":180.0,"gender":"male","name":"Dax","shop":"cyberblock:afterlife_arms"}]}"""));
		assertEquals(new HubNpcs.Figure("bartender", BARTENDER, 21.5, 65.0, 4.5, 0F, 2, "Juno", null), f.getFirst());
		assertEquals(Identifier.fromNamespaceAndPath("cyberblock", "afterlife_arms"), f.get(1).shop());
		assertEquals(1, f.get(1).gender());
		assertThrows(IllegalArgumentException.class, () -> HubNpcs.parse(json("{\"origin\":[0,64,0],\"figures\":[{\"id\":\"a\",\"role\":\"cyberblock:x\",\"pos\":[0,0,0]},"
			+ "{\"id\":\"a\",\"role\":\"cyberblock:x\",\"pos\":[0,0,0]}]}")), "ids are unique");
		assertEquals(List.of(), HubNpcs.parse(json("{\"origin\":[0,64,0]}")));
	}
}
