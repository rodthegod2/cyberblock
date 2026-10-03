package dev.cyberblock;

import net.minecraft.world.phys.Vec3;

/**
 * What a hit on a proxy means for Cyberpunk: which {"t":"hit"} kind it is (or that it is not a hit at all), how much of it
 * counts after vanilla's damage cooldown, and the impulse in m/s. Plain numbers in and out, so it can be tested.
 */
public final class ProxyDamage {
	/** Who is behind the damage: a player, a mob, or nobody (lava, suffocation, falling, TNT nobody lit). */
	public enum Actor {
		PLAYER, MOB, OTHER
	}

	/** What struck: the attacker's body or weapon, an arrow, a thrown trident, or any other projectile. */
	public enum Medium {
		BODY, ARROW, TRIDENT, PROJECTILE
	}

	/** Impulse of a melee hit without knockback, in m/s; each knockback level adds one more of it. */
	static final double MELEE_SPEED = 6.0;
	/** An arrow or trident passes this fraction of its speed (m/s) on. */
	static final double PROJECTILE_SHARE = 0.5;
	/** Minecraft speeds are blocks per tick, the host wants m/s. */
	private static final double TICKS_PER_SECOND = 20.0;
	/** No hit pushes harder than this. */
	static final double MAX_IMPULSE = 100.0;

	private ProxyDamage() {
	}

	/** The hit kind (melee, arrow, trident, mob, explosion, fire), or null when the damage is not Minecraft's to deal: only players and mobs hurt proxies. */
	public static String kind(final boolean explosion, final boolean fire, final Medium medium, final Actor actor) {
		if (actor == Actor.OTHER) {
			return null;
		}

		if (explosion) {
			return "explosion";
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

	/**
	 * The part of a hit that counts under vanilla's damage cooldown: while it runs only a hit stronger than the last one
	 * counts, and only by the difference; otherwise the whole hit.
	 */
	public static float afterCooldown(final float damage, final float lastDamage, final boolean cooling) {
		return cooling ? Math.max(0.0F, damage - lastDamage) : damage;
	}

	/** Away from the attacker along (dx, dz), 6 m/s plus 6 m/s per knockback level. */
	public static Vec3 melee(final double dx, final double dz, final double knockbackLevel) {
		double length = Math.hypot(dx, dz);
		if (!(length > 1.0E-6) || !Double.isFinite(knockbackLevel)) {
			return Vec3.ZERO;
		}

		return new Vec3(dx, 0.0, dz).scale(MELEE_SPEED * (1.0 + Math.max(0.0, knockbackLevel)) / length);
	}

	/** An arrow's or trident's velocity (blocks per tick) times its share, in m/s. */
	public static Vec3 projectile(final Vec3 velocity) {
		return capped(velocity.scale(TICKS_PER_SECOND * PROJECTILE_SHARE));
	}

	/** Away from the blast centre, 2 m/s per point of damage. */
	public static Vec3 blast(final Vec3 away, final double damage) {
		double length = away.length();
		if (!(length > 1.0E-6) || !Double.isFinite(damage)) {
			return Vec3.ZERO;
		}

		return capped(away.scale(2.0 * Math.max(0.0, damage) / length));
	}

	/** The headhunter perk's multiplier, and how close to a pedestrian's top (m) the arrow must be. */
	static final float HEADSHOT = 2.0F;
	static final double HEAD_BAND = 0.35;

	/** The gorilla_arms perk: a melee push twice as strong on a pedestrian, three times on a vehicle (at most MAX_IMPULSE). */
	public static Vec3 gorillaArms(final Vec3 impulse, final boolean vehicle) {
		return capped(impulse.scale(vehicle ? 3.0 : 2.0));
	}

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
