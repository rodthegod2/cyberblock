package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MobRulesTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static MobRules.Seen seen(final int id, final double distance) {
		return new MobRules.Seen(id, "zombie", distance, 64.0, 0.0, 90.0F, 0.6F, 1.95F, 20.0F, 20.0F, distance * distance);
	}

	@Test
	void hostileGoalDrivenMobsFight() {
		assertTrue(MobRules.fighter(true, true, "zombie"));
		assertTrue(MobRules.fighter(true, true, "skeleton"));
		assertTrue(MobRules.fighter(true, true, "creeper"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"piglin", "piglin_brute", "hoglin", "zoglin", "breeze", "creaking", "warden"})
	void brainDrivenHostilesDoNot(final String path) {
		assertFalse(MobRules.fighter(true, true, path));
	}

	@Test
	void neitherDoPeacefulMobsNorNonMobs() {
		assertFalse(MobRules.fighter(true, false, "cow"));
		assertFalse(MobRules.fighter(false, true, "proxy_human"));
	}

	@Test
	void heldOutsideTheProbedRingOrOverTheVoid() {
		assertFalse(MobRules.held(10.0, 10.0, true));
		assertFalse(MobRules.held(22.0, 0.0, true)); // the edge is still in
		assertTrue(MobRules.held(22.01, 0.0, true));
		assertTrue(MobRules.held(16.0, 16.0, true)); // 22.6 diagonally
		assertTrue(MobRules.held(0.0, 0.0, false));
		assertTrue(MobRules.held(-30.0, 0.0, false));
	}

	/** Mobs only hunt the NPCs on the probed ring around Steve: one that chased further would be held at its edge, and a target beyond it. */
	@Test
	void proxiesAreOnlyHuntedWithinTheProbedRing() {
		assertTrue(MobRules.proxyInReach(0.0, 0.0));
		assertTrue(MobRules.proxyInReach(-22.0, 0.0), "the edge is still in");
		assertFalse(MobRules.proxyInReach(0.0, 22.01));
		assertFalse(MobRules.proxyInReach(16.0, -16.0), "22.6 diagonally");
		assertFalse(MobRules.proxyInReach(48.0, 0.0), "in the mob's follow range, but off the ground the host probes");
	}

	@Test
	void mobsPreferHostilePedestriansThenTheNearest() {
		assertTrue(MobRules.preferred(true, 900.0, false, 4.0), "a hostile one far off beats a peaceful one beside it");
		assertFalse(MobRules.preferred(false, 4.0, true, 900.0));
		assertTrue(MobRules.preferred(false, 4.0, false, 9.0), "peaceful: the nearer");
		assertFalse(MobRules.preferred(false, 9.0, false, 4.0));
		assertTrue(MobRules.preferred(true, 4.0, true, 9.0), "both hostile: the nearer");
		assertFalse(MobRules.preferred(true, 4.0, true, 4.0), "a tie keeps the one found first");
	}

	@Test
	void reportsAtMost12NearestFirstWithin48() {
		List<MobRules.Seen> all = new ArrayList<>();
		for (int i = 20; i > 0; i--) {
			all.add(seen(i, i * 2.0)); // 40, 38, ... 2 m
		}

		all.add(seen(99, 48.5)); // out of range
		List<MobRules.Seen> near = MobRules.nearest(all);
		assertEquals(12, near.size());
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), near.stream().map(MobRules.Seen::id).toList());
	}

	@Test
	void mobsJson() {
		JsonObject m = json(Wire.mobs(MobRules.nearest(List.of(seen(7, 3.0), seen(5, 1.0)))));
		assertEquals("mobs", m.get("t").getAsString());
		JsonArray list = m.getAsJsonArray("list");
		assertEquals(2, list.size());
		JsonObject first = list.get(0).getAsJsonObject();
		assertEquals(5, first.get("id").getAsInt());
		assertEquals("zombie", first.get("kind").getAsString());
		assertEquals(1.0, first.getAsJsonArray("p").get(0).getAsDouble());
		assertEquals(90.0, first.get("yaw").getAsDouble());
		assertEquals(0.6, first.get("w").getAsDouble(), 1e-6);
		assertEquals(1.95, first.get("h").getAsDouble(), 1e-6);
		assertEquals(20.0, first.get("hp").getAsDouble());
		assertEquals(20.0, first.get("maxHp").getAsDouble());
		assertEquals(7, list.get(1).getAsJsonObject().get("id").getAsInt());
		assertEquals("{\"t\":\"mobs\",\"list\":[]}", Wire.mobs(List.of()));
	}

	@Test
	void mobsJsonStaysInTheProtocolLimits() {
		MobRules.Seen huge = new MobRules.Seen(1, "Modded.Thing", 0.0, Double.NaN, 0.0, Float.NaN, 40.0F, -1.0F, 5000.0F, 1.0E9F, 0.0);
		JsonObject e = json(Wire.mobs(List.of(huge))).getAsJsonArray("list").get(0).getAsJsonObject();
		assertEquals("mob", e.get("kind").getAsString());
		assertEquals(0.0, e.getAsJsonArray("p").get(1).getAsDouble());
		assertEquals(0.0, e.get("yaw").getAsDouble());
		assertEquals(16.0, e.get("w").getAsDouble());
		assertEquals(0.0, e.get("h").getAsDouble());
		assertEquals(2048.0, e.get("hp").getAsDouble());
		assertEquals(2048.0, e.get("maxHp").getAsDouble());
	}

	@Test
	void hitCarriesTheMob() {
		JsonObject hit = json(Wire.hit("42", 3.0, "mob", 1.0, 0.0, 0.0, 17));
		assertEquals(17, hit.get("mob").getAsInt());
		assertEquals("42", hit.get("target").getAsString());
		assertEquals("mob", hit.get("kind").getAsString());
		assertFalse(json(Wire.hit("42", 3.0, "melee", 1.0, 0.0, 0.0, -1)).has("mob"));
		assertEquals(Wire.hit("42", 3.0, "melee", 1.0, 0.0, 0.0), Wire.hit("42", 3.0, "melee", 1.0, 0.0, 0.0, -1));
	}

	/** A mob's fire (a ghast's fireball) is a "fire" hit, and it names the mob like any other blow: the puppet in Night City is the attacker. */
	@Test
	void aMobsFireNamesTheMobToo() {
		assertEquals("fire", ProxyDamage.kind(false, true, ProxyDamage.Medium.PROJECTILE, ProxyDamage.Actor.MOB));
		JsonObject hit = json(Wire.hit("42", 6.0, "fire", 0.0, 0.0, 0.0, 17));
		assertEquals("fire", hit.get("kind").getAsString());
		assertEquals(17, hit.get("mob").getAsInt());
	}

	@Test
	void mobdmgParses() {
		MobRules.Damage d = MobRules.parseDamage(json("{'t':'mobdmg','id':12,'frac':0.25,'by':'18446744073709551615'}"));
		assertEquals(12, d.id());
		assertEquals(0.25F, d.frac());
		assertEquals(-1L, d.by()); // 2^64 - 1, unsigned
		assertEquals(0L, MobRules.parseDamage(json("{'id':12,'frac':4,'by':null}")).by());
		assertEquals(0L, MobRules.parseDamage(json("{'id':0,'frac':0}")).by());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{'id':1,'frac':1,'by':5}", "{'id':1,'frac':1,'by':'-5'}", "{'id':1,'frac':1,'by':'18446744073709551616'}", "{'id':1,'frac':1,'by':'abc'}"})
	void aBadByOnlyLosesTheAttacker(final String m) {
		MobRules.Damage d = MobRules.parseDamage(json(m));
		assertEquals(1.0F, d.frac());
		assertEquals(0L, d.by());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{'frac':1}", "{'id':-1,'frac':1}", "{'id':1.5,'frac':1}", "{'id':'3','frac':1}", "{'id':3e10,'frac':1}", "{'id':1}",
		"{'id':1,'frac':-0.1}", "{'id':1,'frac':4.01}", "{'id':1,'frac':NaN}", "{'id':1,'frac':Infinity}", "{'id':1,'frac':'1'}"})
	void badMobdmgIsDropped(final String m) {
		assertNull(MobRules.parseDamage(json(m)));
	}
}
