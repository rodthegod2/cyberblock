package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HostDamageTest {
	private static HostDamage.Hit parse(final String json) {
		JsonObject m = JsonParser.parseString(json).getAsJsonObject();
		return HostDamage.parse(m);
	}

	@Test
	void kindsMapToVanillaDamageTypes() {
		assertEquals(DamageTypes.MOB_PROJECTILE, HostDamage.type("bullet"));
		assertEquals(DamageTypes.EXPLOSION, HostDamage.type("explosion"));
		assertEquals(DamageTypes.MOB_ATTACK, HostDamage.type("melee"));
		assertEquals(DamageTypes.FALL, HostDamage.type("fall"));
		assertEquals(DamageTypes.ON_FIRE, HostDamage.type("fire"));
		assertEquals(DamageTypes.GENERIC, HostDamage.type("vehicle"));
		assertEquals(DamageTypes.GENERIC, HostDamage.type("other"));
		assertEquals(DamageTypes.GENERIC, HostDamage.type("laser"), "a kind from a newer host is generic damage");
	}

	@Test
	void parsesTheProtocolExample() {
		HostDamage.Hit h = parse("{\"t\":\"dmg\",\"frac\":0.25,\"kind\":\"bullet\",\"from\":[-1250.5,66.6,805.0]}");
		assertEquals(0.25F, h.frac());
		assertEquals("bullet", h.kind());
		assertEquals(new Vec3(-1250.5, 66.6, 805.0), h.from());
	}

	@Test
	void fracIsZeroToFourAndFinite() {
		assertNotNull(parse("{\"frac\":0,\"kind\":\"fall\"}"));
		assertNotNull(parse("{\"frac\":4,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":4.01,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":-0.1,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":1e999,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":NaN,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":Infinity,\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":\"0.5\",\"kind\":\"fall\"}"), "a string is not a number");
		assertNull(parse("{\"kind\":\"fall\"}"));
		assertNull(parse("{\"frac\":null,\"kind\":\"fall\"}"));
	}

	@Test
	void aMissingOrBadKindIsOther() {
		assertEquals("other", parse("{\"frac\":0.5}").kind());
		assertEquals("other", parse("{\"frac\":0.5,\"kind\":7}").kind());
	}

	/** A fall or a fire has no direction: a position would knock Steve back from where V's damage was reported. */
	@Test
	void fallAndFireHaveNoSourcePosition() {
		Holder<DamageType> fall = Holder.direct(new DamageType("fall", 0.0F));
		assertNull(HostDamage.source(fall, parse("{\"frac\":0.5,\"kind\":\"fall\",\"from\":[1,64,2]}")).getSourcePosition());
		Holder<DamageType> fire = Holder.direct(new DamageType("onFire", 0.0F));
		assertNull(HostDamage.source(fire, parse("{\"frac\":0.5,\"kind\":\"fire\",\"from\":[1,64,2]}")).getSourcePosition());
		Holder<DamageType> explosion = Holder.direct(new DamageType("explosion", 0.1F));
		assertEquals(new Vec3(1, 64, 2),
			HostDamage.source(explosion, parse("{\"frac\":0.5,\"kind\":\"explosion\",\"from\":[1,64,2]}")).getSourcePosition());
		Holder<DamageType> mob = Holder.direct(new DamageType("mob", 0.1F));
		assertEquals(new Vec3(1, 64, 2),
			HostDamage.source(mob, parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[1,64,2]}")).getSourcePosition());
	}

	@Test
	void fromIsOptionalAndABadOneJustLosesTheDirection() {
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":null}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\"}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[1,2]}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[NaN,2,3]}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[\"1\",2,3]}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[3e7,2,3]}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[1,30000,3]}").from());
		assertNull(parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":5}").from());
		assertEquals(0.5F, parse("{\"frac\":0.5,\"kind\":\"bullet\",\"from\":[NaN,2,3]}").frac(), "the damage still lands");
	}
}
