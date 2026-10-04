package dev.cyberblock.mode.heat;

/**
 * One Server Heat tier (megaplan 8-T1, content kind {@code heat_tier}): the meter value it starts at, how fast the meter cools while
 * it is the current tier, the real wanted level hosts get (Phase 0 e), and the Minecraft squads that answer it.
 *
 * @param tier 1..5
 * @param threshold the meter value (1..100) at which this tier starts
 * @param decay meter points lost per second while this is the tier (after the quiet time)
 * @param wanted the wanted level (0..5) a host's real Night City gets at this tier
 */
public record HeatTier(int tier, double threshold, double decay, int wanted, Response response) {
	/**
	 * Who answers the tier in the Minecraft world (squads for Minecraft-only players and for hosts whose real wanted level is not
	 * confirmed):
	 *
	 * @param patrol NCPD officers patrolling around the loudest player while nobody is chased
	 * @param chase how many of the loudest players are chased (ignored with {@code everyone})
	 * @param officers NCPD officers per chased player
	 * @param heavy heavy units (armoured NCPD) per chased player
	 * @param drones air units (NCPD drones) per chased player
	 * @param maxtac Max-Tac troopers on the top chaos player (0: none); a host at the top gets wanted level 5 instead
	 * @param everyone every eligible player is chased (full response)
	 */
	public record Response(int patrol, int chase, int officers, int heavy, int drones, int maxtac, boolean everyone) {
		public static final Response NONE = new Response(0, 0, 0, 0, 0, 0, false);
	}
}
