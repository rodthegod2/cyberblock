package dev.cyberblock.mode.heat;

import java.util.List;

/**
 * One Server Heat tier (megaplan 8-T1, content kind {@code heat_tier}, the schema 8A publishes in
 * {@code shared/content-schemas/heat_tier.schema.json}): when the meter enters and leaves it (hysteresis), how fast it cools while
 * it is the tier, the real wanted level hosts get (Phase 0 e), and the Minecraft squads that answer it.
 *
 * @param tier 1..5
 * @param name the tier's name (the tier-up message)
 * @param enter the meter enters this tier when it rises to this value (1..100)
 * @param exit the meter leaves it when it falls below this value (below {@code enter}, so the tier does not flicker)
 * @param decayPerSecond meter points lost per second while this is the tier, after {@code graceSeconds} without new chaos
 * @param graceSeconds no cooling for this long after the last chaos
 * @param hostWanted the wanted level (0..5) a host's real Night City gets at this tier
 * @param topChaosWanted the wanted level of the top chaos host instead (tier 4: Night City's Max-Tac); -1 when the file has none
 */
public record HeatTier(int tier, String name, double enter, double exit, double decayPerSecond, int graceSeconds, int hostWanted, int topChaosWanted,
	Response response) {
	/** Who the squads go after. */
	public enum Target {
		/** patrols near every player squads may hunt; they do not chase */
		AREA("area"),
		/** the loudest players (recent chaos), at most {@link HeatRules#LOUDEST} */
		LOUDEST("loudest"),
		/** only the top chaos player */
		TOP_CHAOS("top_chaos"),
		/** every player squads may hunt */
		EVERYONE("everyone");

		private final String key;

		Target(final String key) {
			this.key = key;
		}

		public String key() {
			return this.key;
		}

		public static Target of(final String key) {
			for (Target t : values()) {
				if (t.key.equals(key)) {
					return t;
				}
			}

			throw new IllegalArgumentException("response.target must be area, loudest, top_chaos or everyone");
		}
	}

	/** One wave: a {@code spawn_palette} entry sent every {@code everySeconds} to each target. */
	public record Wave(String entry, int everySeconds) {
	}

	/**
	 * The Minecraft response to the tier (squads for Minecraft-only players and for hosts whose real wanted level is not confirmed).
	 *
	 * @param maxAlive at most this many of the tier's squad units at once (all targets together)
	 */
	public record Response(Target target, int maxAlive, List<Wave> waves) {
		public Response {
			waves = List.copyOf(waves);
		}
	}
}
