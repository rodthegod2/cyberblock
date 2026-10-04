package dev.cyberblock.mode.overlord;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which Cyberpunk host games the Overlord may reach (megaplan 8-O1, contract 1.6), pure. A host's game gets an Overlord spawn, hack,
 * glitch or explosion only when the host is an opted-in player of the match ({@code eligible}) and its {@code spawns} toggle (default
 * off) is on, unless the action is the host's own ({@code own}). A remote hack also needs the hacks toggle, an explosion the explosions
 * toggle.
 */
final class HostRules {
	/** A linked player of the level: where its Steve stands and whether it is an opted-in player of the match. */
	record Host(UUID id, double x, double z, boolean eligible) {
		double distance(final double px, final double pz) {
			return Math.hypot(this.x - px, this.z - pz);
		}
	}

	private HostRules() {
	}

	/** Overlord spawns and events into this host's game (and the EMP's glitch). */
	static boolean consents(final boolean eligible, final boolean own, final boolean spawnsOn) {
		return eligible && (own || spawnsOn);
	}

	/** A night_city blackout or traffic jam as the real remote hack: consent plus the hack's own toggles ({@code hackAllowed}). */
	static boolean rhack(final boolean eligible, final boolean own, final boolean spawnsOn, final boolean hackAllowed) {
		return consents(eligible, own, spawnsOn) && hackAllowed;
	}

	/** An airstrike or an Overlord mob's blast as a real explosion: consent plus the explosions toggle. */
	static boolean explosion(final boolean eligible, final boolean own, final boolean spawnsOn, final boolean explosionsOn) {
		return consents(eligible, own, spawnsOn) && (own || explosionsOn);
	}

	/**
	 * The host whose game a Night City spawn at (x, z) goes to, or null for a Minecraft squad: the nearest eligible host within
	 * {@code radius}, and only when it is within {@link Fairness#NEAR} of the spot or no other eligible player is nearer to it (a spot
	 * aimed at a Minecraft-only player gets Minecraft enemies that can fight that player). {@code players}: the level's players near the
	 * spot, hosts included.
	 */
	static @Nullable Host spawnHost(final List<Host> hosts, final List<Fairness.Who> players, final double x, final double z, final double radius) {
		Host best = null;
		for (Host h : hosts) {
			if (h.eligible() && h.distance(x, z) <= radius && (best == null || h.distance(x, z) < best.distance(x, z))) {
				best = h;
			}
		}

		if (best == null || best.distance(x, z) <= Fairness.NEAR) {
			return best;
		}

		double host = best.distance(x, z);
		return players.stream().noneMatch(w -> w.eligible() && w.distance(x, z) < host) ? best : null;
	}
}
