package dev.cyberblock.npc;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What counts toward a fixer gig (megaplan task 7-N1): a Night City person's death for {@code defeat_hostiles} (its figure's name tag
 * {@code faction:<affiliation>} against the gig's faction, case and punctuation ignored), a Breach for {@code breach_device}, a place
 * for {@code reach_landmark}; which gigs a fixer still offers a player; and the time limit. Pure.
 */
final class GigRules {
	private GigRules() {
	}

	static boolean defeatCounts(final Contract c, final String label) {
		return c.type() == Contract.Type.DEFEAT_HOSTILES && label.startsWith("faction:")
			&& NpcJson.key(label.substring(8)).equals(NpcJson.key(c.faction()));
	}

	static boolean breachCounts(final Contract c, final String deviceKind) {
		return c.type() == Contract.Type.BREACH_DEVICE && NpcJson.key(c.kind()).equals(NpcJson.key(deviceKind));
	}

	/**
	 * Within the gig's reach of its landmark: horizontally, and within 16 blocks up or down unless the landmark is {@code approx} (its
	 * height is a guess the Holo-Table's ground snap may land far from, so only x and z count).
	 */
	static boolean reached(final Contract c, final @Nullable Vec3 target, final boolean approx, final Vec3 at) {
		double r = Contract.REACH;
		return c.type() == Contract.Type.REACH_LANDMARK && target != null && (approx || Math.abs(at.y - target.y) <= 16.0)
			&& (at.x - target.x) * (at.x - target.x) + (at.z - target.z) * (at.z - target.z) <= r * r;
	}

	/** The progress after one more, capped at the count. */
	static int next(final int progress, final int count) {
		return Math.min(count, progress + 1);
	}

	/** Whether a fixer offers this gig to a player who has done these (not repeatable ones only once). */
	static boolean offered(final String id, final Contract c, final List<String> finished) {
		return c.repeatable() || !finished.contains(id);
	}

	/** Whether a gig taken at {@code started} (game ticks) has run out of time at {@code now}. */
	static boolean expired(final Contract c, final long started, final long now) {
		return c.timeLimit() > 0 && now - started > c.timeLimit() * 20L;
	}

	/** "2 / 6" for a gig with a count, "" for a reach gig. */
	static String progress(final Contract c, final int progress) {
		return c.type() == Contract.Type.REACH_LANDMARK ? "" : progress + " / " + c.count();
	}
}
