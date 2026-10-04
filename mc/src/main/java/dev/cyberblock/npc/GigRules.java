package dev.cyberblock.npc;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What counts toward a fixer gig (megaplan task 7-N1): a Night City person's death for a {@code defeat} gig (by the figure's name-tag
 * label: {@code faction:<affiliation>}; target {@code any} takes anyone), a Breach for a {@code breach} gig, a place for a {@code
 * reach} gig. Pure.
 */
final class GigRules {
	private GigRules() {
	}

	static boolean defeatCounts(final Contract c, final String label) {
		if (c.type() != Contract.Type.DEFEAT) {
			return false;
		}

		if (NpcJson.key(c.target()).equals("any")) {
			return true;
		}

		return label.startsWith("faction:") && NpcJson.key(label.substring(8)).equals(NpcJson.key(c.target()));
	}

	static boolean breachCounts(final Contract c, final String deviceKind) {
		return c.type() == Contract.Type.BREACH && (NpcJson.key(c.target()).equals("any") || NpcJson.key(c.target()).equals(NpcJson.key(deviceKind)));
	}

	/** Within the gig's radius of its place (horizontally and within 16 blocks up or down). */
	static boolean reached(final Contract c, final @Nullable Vec3 target, final Vec3 at) {
		return c.type() == Contract.Type.REACH && target != null && Math.abs(at.y - target.y) <= 16.0
			&& (at.x - target.x) * (at.x - target.x) + (at.z - target.z) * (at.z - target.z) <= c.radius() * c.radius();
	}

	/** The progress after one more, capped at the count. */
	static int next(final int progress, final int count) {
		return Math.min(count, progress + 1);
	}

	/** "2 / 5" and the contract's text. */
	static String progressLine(final Contract c, final int progress) {
		String count = c.type() == Contract.Type.REACH ? "" : progress + " / " + c.count();
		return c.text().isEmpty() ? count : count.isEmpty() ? c.text() : count + "  " + c.text();
	}
}
