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
	void anIgnitionIsReportedOnceUntilItHasBurntOut() {
		assertTrue(ProxyDamage.igniteDue(0, 0, 160), "flint and steel on a dry NPC");
		assertFalse(ProxyDamage.igniteDue(159, 160, 160), "the same fire set again the next tick");
		assertFalse(ProxyDamage.igniteDue(100, 160, 160), "flint and steel again while it burns");
		assertFalse(ProxyDamage.igniteDue(100, 160, 180), "within a second of what the host was told");
		assertFalse(ProxyDamage.igniteDue(100, 300, 160), "a shorter fire while a longer one burns");
	}

	/** Lava (and a flame block) sets the same fire again every tick: that must not be reported again and again as the report's burn runs down. */
	@Test
	void standingInLavaIsReportedOnceUntilTheBurnRunsOut() {
		for (int tick = 1; tick < 300; tick++) {
			assertFalse(ProxyDamage.igniteDue(300 - tick, 300, 300), "tick " + tick + " in lava");
		}

		assertTrue(ProxyDamage.igniteDue(0, 300, 300), "the host's 15 s are up and the NPC is still in the lava");
	}

	@Test
	void aLongerFireIsReportedWhileAShorterOneBurns() {
		// flint and steel (8 s) and then lava (15 s): the host must hear of the 15 s, not wait for the 8 s to end
		assertTrue(ProxyDamage.igniteDue(100, 160, 300));
		assertTrue(ProxyDamage.igniteDue(100, 160, 181));
		assertFalse(ProxyDamage.igniteDue(100, 160, 180));
	}

	@Test
	void damageCooldownCountsOnlyTheExtra() {
		assertEquals(7.0F, ProxyDamage.afterCooldown(7.0F, 3.0F, false));
		assertEquals(0.0F, ProxyDamage.afterCooldown(3.0F, 7.0F, true));
		assertEquals(0.0F, ProxyDamage.afterCooldown(7.0F, 7.0F, true));
		assertEquals(4.0F, ProxyDamage.afterCooldown(7.0F, 3.0F, true));
	}

	/** Vanilla's sword or fist hit pushes with strength 0.4: Cyberpunk gets 0.4 x 5 = 2 m/s, horizontally away from the attacker. */
	@Test
	void aPlainHitIsTwoMetresPerSecondHorizontally() {
		Vec3 v = ProxyDamage.melee(0.0, 2.0, ProxyDamage.knockbackLevel(0.0, false));
		assertEquals(0.0, v.x, 1e-9);
		assertEquals(0.0, v.y, 1e-9);
		assertEquals(2.0, v.z, 1e-9);
		Vec3 diag = ProxyDamage.melee(3.0, 4.0, 0.0);
		assertEquals(2.0, diag.length(), 1e-9);
		assertEquals(1.2, diag.x, 1e-9);
		assertEquals(1.6, diag.z, 1e-9);
		assertEquals(2.0, ProxyDamage.melee(1.0, 0.0, -3.0).x, 1e-9, "negative knockback does not pull");
		assertEquals(0.4, ProxyDamage.BASE_KNOCKBACK);
		assertEquals(5.0, ProxyDamage.PUSH_PER_STRENGTH);
	}

	/** Every result of plan retest3 section 1.9 (Gorilla Arms halved after the 2026-10-03 re-test), as ProxyEntity works them out (attribute + enchantment, sprint, Gorilla Arms). */
	@Test
	void theKnockbackTiersOfThePlan() {
		// (ATTACK_KNOCKBACK after Knockback, sprinting, Gorilla Arms) -> m/s on a person
		assertEquals(2.0, person(0.0, false, false), 1e-9, "plain hit");
		assertEquals(4.5, person(0.0, true, false), 1e-9, "sprint");
		assertEquals(4.5, person(1.0, false, false), 1e-9, "Knockback I");
		assertEquals(7.0, person(2.0, false, false), 1e-9, "Knockback II");
		assertEquals(7.0, person(2.0, false, false), 1e-9, "the gorilla gauntlet (ATTACK_KNOCKBACK +2)");
		assertEquals(4.5, person(0.0, false, true), 1e-9, "Gorilla Arms: a stagger, not a knockdown on every swing");
		assertEquals(7.0, person(0.0, true, true), 1e-9, "Gorilla Arms + sprint");
		assertEquals(9.5, person(2.0, false, true), 1e-9, "gauntlet + Gorilla Arms");
		assertEquals(12.0, person(2.0, true, true), 1e-9, "gauntlet + Gorilla Arms + sprint: the only melee ragdoll");
		// the Gorilla Arms implant's own ATTACK_KNOCKBACK +2 (empty hand) is its strength on a charged swing: no second one is added
		assertEquals(7.0, implantPunch(false, true, false), 1e-9, "the Gorilla Arms implant through its attribute, charged");
	}

	private static double person(final double modifiedKnockback, final boolean sprint, final boolean gorilla) {
		return ProxyDamage.meleeOn(0.0, 1.0, ProxyDamage.knockbackLevel(modifiedKnockback, sprint), gorilla, false).length();
	}

	/** An empty-hand punch with the implant, as ProxyEntity works it out: its ATTACK_KNOCKBACK +2 is in the attribute. */
	private static double implantPunch(final boolean sprint, final boolean charged, final boolean perk) {
		double level = ProxyDamage.knockbackLevel(2.0, sprint);
		return ProxyDamage.melee(0.0, 1.0, ProxyDamage.personLevel(level, 2.0, charged, perk)).length();
	}

	/** Leftover bug QG: with the implant every quick jab was 7 m/s, a knockdown on every hit. Only the charged punch knocks down. */
	@Test
	void onlyAChargedGorillaImplantPunchKnocksDown() {
		assertTrue(implantPunch(false, false, false) < 7.0, "a quick jab is no knockdown");
		assertEquals(4.5, implantPunch(false, false, false), 1e-9, "a quick jab staggers, like the perk's plain blow");
		assertEquals(7.0, implantPunch(false, true, false), 1e-9, "the charged punch is the knockdown");
		// perk and implant are one shove, never two
		assertEquals(4.5, implantPunch(false, false, true), 1e-9, "quick jab, perk and implant");
		assertEquals(7.0, implantPunch(false, true, true), 1e-9, "charged punch, perk and implant");
		// sprint keeps its table row: Gorilla Arms + sprint 7, and the charged punch adds the implant's full strength
		assertEquals(7.0, implantPunch(true, false, false), 1e-9, "sprinting quick jab = Gorilla Arms + sprint");
		assertEquals(9.5, implantPunch(true, true, false), 1e-9, "sprinting charged punch");
	}

	/** Without the implant on the blow, personLevel is the perk's +0.5 or nothing, whether charged or not (the retest3 table). */
	@Test
	void withoutTheImplantTheChargeChangesNothing() {
		for (boolean charged : new boolean[] {false, true}) {
			assertEquals(0.0, ProxyDamage.personLevel(0.0, 0.0, charged, false), 1e-9);
			assertEquals(0.5, ProxyDamage.personLevel(0.0, 0.0, charged, true), 1e-9);
			assertEquals(1.5, ProxyDamage.personLevel(1.0, 0.0, charged, true), 1e-9, "gauntlet + perk");
		}
	}

	/** Vanilla 26.3 Player.attack: a strong (charged) attack is an attack strength scale above 0.9. */
	@Test
	void aChargedSwingIsVanillasStrongAttack() {
		assertTrue(ProxyDamage.strongSwing(1.0F));
		assertTrue(ProxyDamage.strongSwing(0.91F));
		assertFalse(ProxyDamage.strongSwing(0.9F));
		assertFalse(ProxyDamage.strongSwing(0.3F));
		assertFalse(ProxyDamage.strongSwing(Float.NaN));
	}

	/** Outside a running Player.attack (the slam on L, a lunge) every blow counts as charged: those keep their strength. */
	@Test
	void onlyTheSwingingPlayersUnchargedSwingIsUncharged() {
		Object steve = new Object();
		Object alex = new Object();
		assertTrue(ProxyDamage.charged(steve), "no swing running");
		ProxyDamage.swing(steve, false);
		assertFalse(ProxyDamage.charged(steve));
		assertTrue(ProxyDamage.charged(alex), "another player's blow");
		ProxyDamage.endSwing(steve);
		assertTrue(ProxyDamage.charged(steve));
		ProxyDamage.swing(steve, true);
		assertTrue(ProxyDamage.charged(steve));
		ProxyDamage.endSwing(steve);
	}

	@Test
	void mobsNeverGetTheSprintBonus() {
		// ProxyEntity passes sprinting only for players
		assertEquals(0.0, ProxyDamage.knockbackLevel(0.0, false));
		assertEquals(0.5, ProxyDamage.knockbackLevel(0.0, true));
		assertEquals(1.0, ProxyDamage.knockbackLevel(2.0, false), "a mob with a Knockback II sword");
	}

	@Test
	void gorillaArmsTriplesThePushOnAVehicle() {
		assertEquals(2.0, ProxyDamage.meleeOn(1.0, 0.0, 0.0, false, true).x, 1e-9);
		assertEquals(6.0, ProxyDamage.meleeOn(1.0, 0.0, 0.0, true, true).x, 1e-9);
		assertEquals(21.0, ProxyDamage.meleeOn(1.0, 0.0, 1.0, true, true).x, 1e-9);
		assertEquals(ProxyDamage.MAX_IMPULSE, ProxyDamage.meleeOn(0.0, 1.0, 50.0, true, true).length(), 1e-9);
	}

	@Test
	void meleeWithoutADirectionOrWithGarbageIsNoImpulse() {
		assertEquals(Vec3.ZERO, ProxyDamage.melee(0.0, 0.0, 1.0));
		assertEquals(Vec3.ZERO, ProxyDamage.melee(Double.NaN, 1.0, 1.0));
		assertEquals(Vec3.ZERO, ProxyDamage.melee(1.0, 0.0, Double.NaN));
		assertEquals(Vec3.ZERO, ProxyDamage.melee(Double.POSITIVE_INFINITY, 1.0, 1.0));
	}

	/** Plan retest3 section 1.9: an arrow pushes (0.4 + 0.6 x Punch) x 5 m/s along its horizontal flight, not with its speed. */
	@Test
	void projectilesPushLikeVanillaWhateverTheirSpeed() {
		// a full draw is 3 blocks per tick = 60 m/s: still 2 m/s without Punch
		Vec3 v = ProxyDamage.projectile(new Vec3(0.0, 0.0, 3.0), 0.0);
		assertEquals(new Vec3(0.0, 0.0, 2.0), v);
		assertEquals(2.0, ProxyDamage.projectile(new Vec3(0.0, 0.0, 0.5), 0.0).z, 1e-9, "a weak shot pushes the same");
		assertEquals(5.0, ProxyDamage.projectile(new Vec3(0.0, 0.0, 3.0), 1.0).z, 1e-9, "Punch I");
		assertEquals(8.0, ProxyDamage.projectile(new Vec3(0.0, 0.0, 3.0), 2.0).z, 1e-9, "Punch II");
		Vec3 down = ProxyDamage.projectile(new Vec3(0.3, -0.6, 0.0), 0.0);
		assertEquals(2.0, down.x, 1e-9);
		assertEquals(0.0, down.y, 1e-9, "horizontal only");
		assertEquals(0.6, ProxyDamage.PUNCH_STRENGTH);
	}

	@Test
	void aProjectileWithoutAHorizontalDirectionOrWithGarbageIsNoImpulse() {
		assertEquals(Vec3.ZERO, ProxyDamage.projectile(new Vec3(0.0, -2.0, 0.0), 1.0));
		assertEquals(Vec3.ZERO, ProxyDamage.projectile(new Vec3(Double.NaN, 0.0, 1.0), 0.0));
		assertEquals(2.0, ProxyDamage.projectile(new Vec3(1.0, 0.0, 0.0), Double.NaN).x, 1e-9, "a broken punch counts as none");
		assertEquals(2.0, ProxyDamage.projectile(new Vec3(1.0, 0.0, 0.0), -1.0).x, 1e-9);
	}

	@Test
	void noImpulseIsBeyondTheCap() {
		assertEquals(ProxyDamage.MAX_IMPULSE, ProxyDamage.melee(0.0, 1.0, 1000.0).length(), 1e-9);
		assertEquals(ProxyDamage.MAX_IMPULSE, ProxyDamage.projectile(new Vec3(0.0, 0.0, 1.0), 1000.0).length(), 1e-9);
	}
}
