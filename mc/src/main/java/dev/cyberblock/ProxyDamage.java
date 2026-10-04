package dev.cyberblock;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a hit on a proxy means for Cyberpunk: which {"t":"hit"} kind it is (or that it is not a hit at all), how much of it
 * counts after vanilla's damage cooldown, and the impulse in m/s. Plain numbers in and out, so it can be tested.
 */
public final class ProxyDamage {
	/** Who is behind the damage: a player, a mob, or nobody (lava, suffocation, falling). */
	public enum Actor {
		PLAYER, MOB, OTHER
	}

	/** What struck: the attacker's body or weapon, an arrow, a thrown trident, or any other projectile. */
	public enum Medium {
		BODY, ARROW, TRIDENT, PROJECTILE
	}

	/**
	 * Vanilla's knockback strength of every hit (LivingEntity.hurt pushes with 0.4) and how many m/s Cyberpunk gets per unit of
	 * strength (plan retest3 section 1.9, F7): a plain hit is 2 m/s (a flinch in Cyberpunk), sprint, Knockback I or Gorilla Arms 4.5
	 * (a stagger), Knockback II, the gauntlet or Gorilla Arms and sprint 7 (a knockdown), gauntlet and Gorilla Arms 9.5 (a knockdown),
	 * gauntlet, Gorilla Arms and sprint 12 (Cyberpunk ragdolls only from 10 m/s).
	 */
	static final double BASE_KNOCKBACK = 0.4;
	static final double PUSH_PER_STRENGTH = 5.0;
	/**
	 * Gorilla Arms on a person: half a unit more strength, as much as a sprint. In-game 2026-10-03 the full unit of the plan made
	 * every plain swing with the perk 7 m/s, a knockdown on every hit; a stagger is what a stronger plain blow earns. On a vehicle
	 * the push is tripled instead.
	 */
	static final double GORILLA_STRENGTH = 0.5;
	static final double GORILLA_VEHICLE = 3.0;
	/**
	 * The Gorilla Arms implant (leftover bug QG): its empty-hand ATTACK_KNOCKBACK +2 (CyberwareEffects, plan retest2 section 1.7)
	 * made every jab 7 m/s, a knockdown on every hit. As with the perk (above), a plain blow earns a stagger and the knockdown is
	 * the strong blow: only a charged swing (vanilla's strong attack, attack strength scale above STRONG_SWING) gets the implant's
	 * full strength; a quick jab gets GORILLA_STRENGTH instead. Minecraft mobs keep the attribute as it is.
	 */
	static final float STRONG_SWING = 0.9F;
	/** Each Punch level of the bow adds this much strength to an arrow (vanilla AbstractArrow.doKnockback: punch x 0.6). */
	static final double PUNCH_STRENGTH = 0.6;
	/** No hit pushes harder than this. */
	static final double MAX_IMPULSE = 100.0;

	private ProxyDamage() {
	}

	/**
	 * The hit kind (melee, arrow, trident, mob, fire), or null when the damage is not Minecraft's to deal: only players and mobs hurt
	 * proxies, and never an explosion. The host turns every explosion into a real Cyberpunk one ({"t":"explosion"}), which hurts the NPC
	 * itself: a hit for it as well would apply the blast twice.
	 */
	public static String kind(final boolean explosion, final boolean fire, final Medium medium, final Actor actor) {
		if (explosion || actor == Actor.OTHER) {
			return null;
		}

		if (fire) {
			return "fire";
		}

		return switch (medium) {
			case TRIDENT -> "trident";
			case ARROW -> "arrow";
			default -> actor == Actor.PLAYER ? "melee" : "mob";
		};
	}

	/** A fire must be longer than the one the host was told of by this much (ticks) to be reported again while that one burns. */
	static final int REIGNITE_MARGIN = 20;

	/**
	 * Whether a fire of remainingTicks is for the host to hear: when the earlier ignition has burnt out (cooldownTicks left of the
	 * reportedTicks it was told), or when this one is longer than that by more than a second (lava after flint and steel). Vanilla sets
	 * the same fire again every tick an entity stands in lava or a flame: measured against the length reported (not what is left of it)
	 * those are never new, and one that goes on is reported once more when the host's burn has run out.
	 */
	public static boolean igniteDue(final int cooldownTicks, final int reportedTicks, final int remainingTicks) {
		return cooldownTicks <= 0 || remainingTicks > reportedTicks + REIGNITE_MARGIN;
	}

	/**
	 * The part of a hit that counts under vanilla's damage cooldown: while it runs only a hit stronger than the last one
	 * counts, and only by the difference; otherwise the whole hit.
	 */
	public static float afterCooldown(final float damage, final float lastDamage, final boolean cooling) {
		return cooling ? Math.max(0.0F, damage - lastDamage) : damage;
	}

	/**
	 * The extra knockback strength of a blow as vanilla's Player.attack works it out: (the weapon's ATTACK_KNOCKBACK after its
	 * Knockback enchantment, plus 1 while sprinting) x 0.5. Mobs never get the sprint part.
	 */
	public static double knockbackLevel(final double modifiedKnockback, final boolean sprinting) {
		return (modifiedKnockback + (sprinting ? 1.0 : 0.0)) * 0.5;
	}

	/** Away from the attacker along (dx, dz), horizontally: (0.4 + knockbackLevel) x 5 m/s. */
	public static Vec3 melee(final double dx, final double dz, final double knockbackLevel) {
		return push(dx, dz, BASE_KNOCKBACK + Math.max(0.0, knockbackLevel));
	}

	/**
	 * A blow on a proxy: on a person Gorilla Arms adds GORILLA_STRENGTH to the strength, on a vehicle it triples the push (at most
	 * MAX_IMPULSE either way).
	 */
	public static Vec3 meleeOn(final double dx, final double dz, final double knockbackLevel, final boolean gorillaArms, final boolean vehicle) {
		if (vehicle) {
			Vec3 push = melee(dx, dz, knockbackLevel);
			return gorillaArms ? capped(push.scale(GORILLA_VEHICLE)) : push;
		}

		return melee(dx, dz, knockbackLevel + (gorillaArms ? GORILLA_STRENGTH : 0.0));
	}

	/**
	 * The knockback level of a player's blow on a person, Gorilla Arms weighed in. implantKnockback: what the implant's
	 * ATTACK_KNOCKBACK modifier put into knockbackLevel's attribute (0 when it is off). With it, a charged swing keeps it all and a
	 * quick jab trades its share for GORILLA_STRENGTH; perk and implant are one shove, never two. Without it the perk adds
	 * GORILLA_STRENGTH, charged or not.
	 */
	public static double personLevel(final double knockbackLevel, final double implantKnockback, final boolean charged, final boolean perk) {
		if (!(implantKnockback > 0.0)) {
			return knockbackLevel + (perk ? GORILLA_STRENGTH : 0.0);
		}

		return charged ? knockbackLevel : knockbackLevel - knockbackLevel(implantKnockback, false) + GORILLA_STRENGTH;
	}

	/** Vanilla 26.3 Player.attack's strong attack: the attack strength scale it read before resetting the ticker is above 0.9. */
	public static boolean strongSwing(final float attackStrengthScale) {
		return attackStrengthScale > STRONG_SWING;
	}

	/**
	 * The player whose Player.attack is running as an uncharged swing (CyberPlayerMixin), null otherwise. Server thread only: vanilla
	 * resets the attack strength before the target is hurt, so the swing's charge is kept here for ProxyEntity to read.
	 */
	private static @Nullable Object unchargedSwing;

	/** A player's Player.attack read its attack strength (server side): `strong` is strongSwing of it. */
	public static void swing(final Object player, final boolean strong) {
		unchargedSwing = strong ? null : player;
	}

	/** That player's Player.attack returned. */
	public static void endSwing(final Object player) {
		if (unchargedSwing == player) {
			unchargedSwing = null;
		}
	}

	/** Whether this attacker's blow counts as charged: everything but an uncharged swing in progress (the slam and lunge keep theirs). */
	public static boolean charged(final Object attacker) {
		return attacker != unchargedSwing;
	}

	/**
	 * An arrow, trident or other projectile: (0.4 + 0.6 x Punch level) x 5 m/s along its horizontal direction of flight, whatever its
	 * speed (vanilla pushes the same way). A projectile falling straight down pushes nothing.
	 */
	public static Vec3 projectile(final Vec3 velocity, final double punch) {
		return push(velocity.x, velocity.z, BASE_KNOCKBACK + PUNCH_STRENGTH * Math.max(0.0, Double.isFinite(punch) ? punch : 0.0));
	}

	/** strength x PUSH_PER_STRENGTH m/s along (dx, dz), horizontally; nothing without a direction or with a broken strength. */
	private static Vec3 push(final double dx, final double dz, final double strength) {
		double length = Math.hypot(dx, dz);
		if (!(length > 1.0E-6) || !Double.isFinite(strength) || !Double.isFinite(length)) {
			return Vec3.ZERO;
		}

		return capped(new Vec3(dx, 0.0, dz).scale(strength * PUSH_PER_STRENGTH / length));
	}

	/** The headhunter perk's multiplier, and how close to a pedestrian's top (m) the arrow must be. */
	static final float HEADSHOT = 2.0F;
	static final double HEAD_BAND = 0.35;

	/** An arrow at arrowY (its position when it hits) is a head shot on a pedestrian standing at feetY this tall. */
	public static boolean headshot(final double arrowY, final double feetY, final double height) {
		return Math.abs(arrowY - (feetY + height)) <= HEAD_BAND;
	}

	private static Vec3 capped(final Vec3 v) {
		double length = v.length();
		if (!Double.isFinite(length)) {
			return Vec3.ZERO;
		}

		return length > MAX_IMPULSE ? v.scale(MAX_IMPULSE / length) : v;
	}
}
