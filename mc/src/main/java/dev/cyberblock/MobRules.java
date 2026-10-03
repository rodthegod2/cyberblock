package dev.cyberblock;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 3 (plan 2026-10-03 M2), the rules of Minecraft's mobs fighting Night City as plain numbers, so they can be tested:
 * which mobs fight, which are held off unknown ground, which the host hears about ({"t":"mobs"}), and the host's damage to
 * them ({"t":"mobdmg"}). MobWar applies them to the world.
 */
public final class MobRules {
	/** Hostiles that pick targets from brain memories, not goals: a target goal would never run (MobWar reference). */
	static final Set<String> BRAIN_MOBS = Set.of("piglin", "piglin_brute", "hoglin", "zoglin", "breeze", "creaking", "warden");
	/** Mobs hunt proxies at least this far away (blocks), and the host hears about the fighters this close to Steve (m). */
	static final double RANGE = 48.0;
	static final int MAX_REPORTED = 12;
	/** Night City's ground is only probed around V: a fighter further than this from Steve (horizontally) is held. */
	static final double HOLD_RANGE = 22.0;
	/**
	 * A fighter with no block this far below its feet is over the void (outside the probed ground), so it is held. Deeper than the
	 * plan's 4: a mob chasing a target drops up to ~16 blocks (Mob.getMaxFallDistance), and one held at a ledge above a probed
	 * street would hang there for good.
	 */
	static final int GROUND_DEPTH = 32;
	/** The host clamps to 0..4 (a hit can be several times a puppet's health); anything else is garbage. */
	static final double MAX_FRAC = 4.0;
	private static final Pattern KIND = Pattern.compile("[a-z0-9_]{1,32}");
	private static final Pattern DECIMAL_ID = Pattern.compile("[0-9]{1,20}");

	/** One fighter near Steve: what {"t":"mobs"} says about it. */
	public record Seen(int id, String kind, double x, double y, double z, float yaw, float w, float h, float hp, float maxHp, double distanceSq) {
	}

	/** A host hit on a mob's puppet: frac of the mob's max health, by the Cyberpunk NPC with this id (0: nobody known). */
	public record Damage(int id, float frac, long by) {
	}

	private MobRules() {
	}

	/** A hostile, goal-driven mob (the type path of a vanilla mob, e.g. "zombie"): it joins the fight. */
	public static boolean fighter(final boolean mob, final boolean enemy, final String typePath) {
		return mob && enemy && !BRAIN_MOBS.contains(typePath);
	}

	/**
	 * Held (no gravity, no AI, no velocity) while further than HOLD_RANGE from Steve horizontally or with no ground below, so it never
	 * walks or falls into the void outside the probed area; free again once both are fine.
	 */
	public static boolean held(final double dx, final double dz, final boolean groundBelow) {
		return !groundBelow || dx * dx + dz * dz > HOLD_RANGE * HOLD_RANGE;
	}

	/** The wire kind for a mob type path: the path when it fits [a-z0-9_]{1,32}, else "mob". */
	public static String kind(final String typePath) {
		return KIND.matcher(typePath).matches() ? typePath : "mob";
	}

	/** The fighters the host hears about: within RANGE, nearest first, at most MAX_REPORTED. */
	public static List<Seen> nearest(final List<Seen> all) {
		List<Seen> near = new ArrayList<>();
		for (Seen s : all) {
			if (s.distanceSq() <= RANGE * RANGE) {
				near.add(s);
			}
		}

		near.sort(Comparator.comparingDouble(Seen::distanceSq));
		return near.size() > MAX_REPORTED ? List.copyOf(near.subList(0, MAX_REPORTED)) : near;
	}

	/**
	 * {"t":"mobdmg","id":mob,"frac":0..4,"by":"npc id"|null}, or null when id or frac is bad (then nothing happens). A bad "by"
	 * only loses who did it: the damage still lands.
	 */
	public static Damage parseDamage(final JsonObject m) {
		double id = number(m.get("id"));
		double frac = number(m.get("frac"));
		if (!(id >= 0.0 && id <= Integer.MAX_VALUE && id == Math.floor(id)) || !(frac >= 0.0 && frac <= MAX_FRAC)) {
			return null;
		}

		return new Damage((int) id, (float) frac, id(m.get("by")));
	}

	/** A Cyberpunk entity id (a decimal string), 0 for none, null or anything malformed. */
	static long id(final JsonElement e) {
		if (e == null || e instanceof JsonNull || !(e instanceof JsonPrimitive p) || !p.isString() || !DECIMAL_ID.matcher(p.getAsString()).matches()) {
			return 0L;
		}

		try {
			return Long.parseUnsignedLong(p.getAsString());
		} catch (NumberFormatException tooBig) {
			return 0L;
		}
	}

	private static double number(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
	}
}
