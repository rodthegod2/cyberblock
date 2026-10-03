package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Layer 5: leads, fishing rods, potions and the perk hooks, as the host hears of them. */
class InteractiveItemsTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void leadHeldBySteve() {
		JsonObject m = json(Wire.lead("9007199254740993", null, Tethers.leadLength(false)));
		assertEquals("lead", m.get("t").getAsString());
		assertEquals("9007199254740993", m.get("target").getAsString());
		assertTrue(m.get("anchor").isJsonNull());
		assertEquals(5.0, m.get("length").getAsDouble());
		assertFalse(m.has("release"));
	}

	@Test
	void leadTiedToAFence() {
		JsonObject m = json(Wire.lead("12", new double[] {10.5, 64.0, -3.5}, Tethers.leadLength(true)));
		assertEquals(10.5, m.getAsJsonArray("anchor").get(0).getAsDouble());
		assertEquals(64.0, m.getAsJsonArray("anchor").get(1).getAsDouble());
		assertEquals(-3.5, m.getAsJsonArray("anchor").get(2).getAsDouble());
		assertEquals(8.0, m.get("length").getAsDouble());
	}

	@Test
	void leadReleased() {
		assertEquals("{\"t\":\"lead\",\"target\":\"12\",\"release\":true}", Wire.leadRelease("12"));
		assertTrue(json(Wire.leadRelease("12")).get("release").getAsBoolean());
	}

	@Test
	void pullStrengths() {
		assertEquals(9.0, Tethers.pullStrength(false));
		assertEquals(7.0, Tethers.pullStrength(true));
		JsonObject m = json(Wire.pull("12", 1.0, 65.62, -2.0, Tethers.pullStrength(false)));
		assertEquals("pull", m.get("t").getAsString());
		assertEquals(65.62, m.getAsJsonArray("toward").get(1).getAsDouble());
		assertEquals(9.0, m.get("strength").getAsDouble());
		assertEquals(50.0, json(Wire.pull("12", 0, 0, 0, 80.0)).get("strength").getAsDouble());
	}

	@Test
	void knockStrength() {
		assertEquals(14.4, Tethers.knockStrength(1.2), 1e-9);
		assertEquals(50.0, Tethers.knockStrength(10.0));
		assertEquals(0.0, Tethers.knockStrength(-1.0));
	}

	@Test
	void headhunterBand() {
		// a pedestrian 1.8 tall standing at y 64: the top is 65.8
		assertTrue(ProxyDamage.headshot(65.8, 64.0, 1.8));
		assertTrue(ProxyDamage.headshot(65.45, 64.0, 1.8));
		assertTrue(ProxyDamage.headshot(66.1, 64.0, 1.8));
		assertFalse(ProxyDamage.headshot(66.2, 64.0, 1.8));
		assertFalse(ProxyDamage.headshot(65.44, 64.0, 1.8));
		assertFalse(ProxyDamage.headshot(65.0, 64.0, 1.8));
		assertEquals(2.0F, ProxyDamage.HEADSHOT);
	}

	@Test
	void gorillaArmsScalesTheMeleeImpulse() {
		Vec3 melee = ProxyDamage.melee(1.0, 0.0, 0.0); // 6 m/s
		assertEquals(12.0, ProxyDamage.gorillaArms(melee, false).x, 1e-9);
		assertEquals(18.0, ProxyDamage.gorillaArms(melee, true).x, 1e-9);
		Vec3 strong = ProxyDamage.melee(0.0, 1.0, 10.0); // 66 m/s with knockback
		assertEquals(ProxyDamage.MAX_IMPULSE, ProxyDamage.gorillaArms(strong, true).length(), 1e-9);
	}

	@Test
	void effectMessages() {
		JsonObject speed = json(PlayerEffects.message(Identifier.withDefaultNamespace("speed"), false, 600, false, 1));
		assertEquals("effect", speed.get("t").getAsString());
		assertEquals("speed", speed.get("name").getAsString());
		assertEquals(30.0, speed.get("seconds").getAsDouble());
		assertEquals(1, speed.get("amp").getAsInt());
		assertEquals(86400.0, json(PlayerEffects.message(Identifier.withDefaultNamespace("invisibility"), false, -1, true, 0)).get("seconds").getAsDouble());
		assertEquals(0.0, json(PlayerEffects.message(Identifier.withDefaultNamespace("jump_boost"), true, 600, false, 0)).get("seconds").getAsDouble());
		assertEquals(255, json(Wire.effect("speed", 1.0, 999)).get("amp").getAsInt());
		assertEquals(86400.0, json(Wire.effect("speed", 1e9, 0)).get("seconds").getAsDouble());
	}

	@Test
	void effectsTheProtocolCannotNameAreNotSent() {
		assertNull(PlayerEffects.message(Identifier.fromNamespaceAndPath("othermod", "speed"), false, 20, false, 0));
		assertNull(PlayerEffects.message(Identifier.withDefaultNamespace("speed2"), false, 20, false, 0));
	}
}
