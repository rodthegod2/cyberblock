package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.ProxyDamage.Actor;
import dev.cyberblock.ProxyDamage.Medium;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProxyDamageTest {
	@Test
	void onlyPlayersAndMobsHurtProxies() {
		assertNull(ProxyDamage.kind(false, false, Medium.BODY, Actor.OTHER));
		assertNull(ProxyDamage.kind(true, false, Medium.BODY, Actor.OTHER), "TNT nobody lit: Cyberpunk's own explosion covers it");
		assertNull(ProxyDamage.kind(false, true, Medium.BODY, Actor.OTHER), "lava, burning");
		assertNull(ProxyDamage.kind(false, false, Medium.ARROW, Actor.OTHER), "a dispenser arrow has no owner");
	}

	@Test
	void kindsFollowTheProtocol() {
		assertEquals("melee", ProxyDamage.kind(false, false, Medium.BODY, Actor.PLAYER));
		assertEquals("mob", ProxyDamage.kind(false, false, Medium.BODY, Actor.MOB));
		assertEquals("arrow", ProxyDamage.kind(false, false, Medium.ARROW, Actor.PLAYER));
		assertEquals("arrow", ProxyDamage.kind(false, false, Medium.ARROW, Actor.MOB));
		assertEquals("trident", ProxyDamage.kind(false, false, Medium.TRIDENT, Actor.PLAYER));
		assertEquals("fire", ProxyDamage.kind(false, true, Medium.PROJECTILE, Actor.MOB), "a ghast fireball");
		assertEquals("melee", ProxyDamage.kind(false, false, Medium.PROJECTILE, Actor.PLAYER));
		assertEquals("mob", ProxyDamage.kind(false, false, Medium.PROJECTILE, Actor.MOB));
	}

	/** The host's {"t":"explosion"} is the only source of a blast's damage: a hit for Minecraft's copy of it would count it twice. */
	@Test
	void explosionsAreNeverAHitWhoeverCausedThem() {
		for (Actor actor : Actor.values()) {
			for (Medium medium : Medium.values()) {
				assertNull(ProxyDamage.kind(true, false, medium, actor), actor + " " + medium);
				assertNull(ProxyDamage.kind(true, true, medium, actor), actor + " " + medium + " (a fiery blast)");
			}
		}
	}

	@Test
	void damageCooldownCountsOnlyTheExtra() {
		assertEquals(7.0F, ProxyDamage.afterCooldown(7.0F, 3.0F, false));
		assertEquals(0.0F, ProxyDamage.afterCooldown(3.0F, 7.0F, true));
		assertEquals(0.0F, ProxyDamage.afterCooldown(7.0F, 7.0F, true));
		assertEquals(4.0F, ProxyDamage.afterCooldown(7.0F, 3.0F, true));
	}

	@Test
	void meleePushesAwayAtSixMetresPerSecondPlusSixPerKnockbackLevel() {
		Vec3 v = ProxyDamage.melee(0.0, 2.0, 0.0);
		assertEquals(0.0, v.x, 1e-9);
		assertEquals(0.0, v.y, 1e-9);
		assertEquals(6.0, v.z, 1e-9);
		assertEquals(18.0, ProxyDamage.melee(0.0, 5.0, 2.0).z, 1e-9, "knockback II");
		Vec3 diag = ProxyDamage.melee(3.0, 4.0, 1.0);
		assertEquals(12.0, diag.length(), 1e-9);
		assertEquals(7.2, diag.x, 1e-9);
		assertEquals(9.6, diag.z, 1e-9);
		assertEquals(6.0, ProxyDamage.melee(1.0, 0.0, -3.0).x, 1e-9, "negative knockback does not pull");
	}

	@Test
	void meleeWithoutADirectionOrWithGarbageIsNoImpulse() {
		assertEquals(Vec3.ZERO, ProxyDamage.melee(0.0, 0.0, 1.0));
		assertEquals(Vec3.ZERO, ProxyDamage.melee(Double.NaN, 1.0, 1.0));
		assertEquals(Vec3.ZERO, ProxyDamage.melee(1.0, 0.0, Double.NaN));
	}

	@Test
	void arrowsPassOnHalfTheirSpeedInMetresPerSecond() {
		// a full draw is 3 blocks per tick = 60 m/s
		Vec3 v = ProxyDamage.projectile(new Vec3(0.0, 0.0, 3.0));
		assertEquals(30.0, v.z, 1e-9);
		Vec3 up = ProxyDamage.projectile(new Vec3(0.3, -0.6, 0.0));
		assertEquals(3.0, up.x, 1e-9);
		assertEquals(-6.0, up.y, 1e-9);
	}

	@Test
	void noImpulseIsBeyondTheCap() {
		assertEquals(100.0, ProxyDamage.projectile(new Vec3(0.0, 0.0, 50.0)).length(), 1e-9);
		assertEquals(Vec3.ZERO, ProxyDamage.projectile(new Vec3(Double.NaN, 0.0, 1.0)));
	}
}
