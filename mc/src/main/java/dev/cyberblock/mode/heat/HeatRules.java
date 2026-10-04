package dev.cyberblock.mode.heat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the Server Heat meter (megaplan 8-T1), pure so the tests can drive them: what each kind of chaos adds, the gain cap,
 * the decay, the tier of a value, the wanted level a host gets, and the squads a tier asks for. {@link HeatMeter} and
 * {@link ChaosBoard} hold the state; {@link ServerHeat} wires them to the server.
 */
public final class HeatRules {
	public static final double MAX = 100.0;
	/** The meter gains at most this many points per second, whatever happens (a TNT chain cannot jump it from 0 to 100 at once). */
	public static final double GAIN_PER_SECOND = 15.0;
	/** ...with this much saved up for a burst. */
	public static final double GAIN_BURST = 30.0;
	/** No decay until this long after the last chaos (server ticks: 10 s). */
	public static final long QUIET_TICKS = 200;
	/** Points per second lost below tier 1. */
	public static final double CALM_DECAY = 1.0;
	/** The real wanted level that brings Max-Tac in 2.31 (the top chaos host at a tier with Max-Tac). */
	public static final int MAXTAC_WANTED = 5;

	/** What fed the meter. */
	public enum Source {
		/** a blast in the Minecraft world (TNT, creepers, fireballs, beds, firework bursts) */
		EXPLOSION(3.0),
		/** a host's V killed a hostile in Night City */
		HOSTILE_KILL(1.0),
		/** a host's V killed a civilian in Night City */
		CIVILIAN_KILL(3.0),
		/** a car destroyed in Night City */
		CAR_DESTROYED(4.0),
		/** a failed Breach Protocol on a host's device (BreachResults.FAILURE_HEAT) */
		FAILED_BREACH(5.0),
		/** a player downed or killed another player */
		PVP_KILL(6.0),
		/** a player killed a Minecraft mob that is not a monster (villagers, golems, animals) */
		MOB_KILL(2.0),
		/** a player killed one of the squads Heat sent */
		SQUAD_KILL(2.0),
		/** an operator or another phase (Overlord events, matches) */
		OTHER(0.0);

		private final double amount;

		Source(final double amount) {
			this.amount = amount;
		}

		/** The default points this kind of chaos adds. */
		public double amount() {
			return this.amount;
		}
	}

	/** The built-in tiers (used while no {@code heat_tier} content is loaded). */
	public static final List<HeatTier> BUILT_IN = List.of(
		new HeatTier(1, 20, 0.5, 1, new HeatTier.Response(2, 0, 0, 0, 0, 0, false)),
		new HeatTier(2, 40, 0.4, 2, new HeatTier.Response(0, 2, 2, 0, 0, 0, false)),
		new HeatTier(3, 60, 0.35, 3, new HeatTier.Response(0, 2, 2, 1, 1, 0, false)),
		new HeatTier(4, 80, 0.3, 4, new HeatTier.Response(0, 2, 2, 1, 1, 3, false)),
		new HeatTier(5, 95, 0.25, 5, new HeatTier.Response(0, 0, 2, 1, 1, 3, true)));

	private HeatRules() {
	}

	/** The meter after adding {@code amount} (clamped to 0..100). */
	public static double feed(final double value, final double amount) {
		return Math.clamp(value + (Double.isFinite(amount) ? amount : 0.0), 0.0, MAX);
	}

	/** The tiers sorted by threshold (tier numbers follow the order). */
	public static List<HeatTier> sorted(final List<HeatTier> tiers) {
		List<HeatTier> out = new ArrayList<>(tiers);
		out.sort(Comparator.comparingDouble(HeatTier::threshold).thenComparingInt(HeatTier::tier));
		return out;
	}

	/** The current tier for a meter value: the highest one whose threshold it reached, or null below them all (tier 0). */
	public static @Nullable HeatTier tierOf(final double value, final List<HeatTier> sorted) {
		HeatTier at = null;
		for (HeatTier t : sorted) {
			if (value >= t.threshold()) {
				at = t;
			}
		}

		return at;
	}

	/** The tier number 0..5 for a meter value. */
	public static int tier(final double value, final List<HeatTier> sorted) {
		HeatTier t = tierOf(value, sorted);
		return t == null ? 0 : t.tier();
	}

	/**
	 * The meter after {@code seconds} of cooling: nothing within {@link #QUIET_TICKS} of the last chaos, then the current tier's decay
	 * (or {@link #CALM_DECAY} below tier 1). Never below 0.
	 */
	public static double decay(final double value, final double seconds, final long ticksSinceChaos, final List<HeatTier> sorted) {
		if (ticksSinceChaos < QUIET_TICKS || value <= 0.0) {
			return Math.max(0.0, value);
		}

		HeatTier t = tierOf(value, sorted);
		double rate = t == null ? CALM_DECAY : t.decay();
		return Math.max(0.0, value - rate * seconds);
	}

	/**
	 * The real wanted level for a host (Phase 0 e): the tier's {@code wanted}; the top chaos player at a tier that sends Max-Tac gets
	 * {@link #MAXTAC_WANTED} (Night City's own Max-Tac). 0 below tier 1.
	 */
	public static int wanted(final @Nullable HeatTier tier, final boolean topChaos) {
		if (tier == null) {
			return 0;
		}

		int w = topChaos && tier.response().maxtac() > 0 ? MAXTAC_WANTED : tier.wanted();
		return Math.clamp(w, 0, 5);
	}

	/** A Minecraft squad unit. */
	public enum Unit {
		PATROL, OFFICER, HEAVY, DRONE, MAXTAC
	}

	/** {@code count} units of one kind sent after (or, for a patrol, around) one player. */
	public record Order(UUID target, Unit unit, int count) {
	}

	/**
	 * The squads a tier asks for. {@code ranked}: the players squads may hunt (Minecraft-only players, and hosts whose real wanted level
	 * is not confirmed), loudest first, each with its recent chaos; {@code top} the server's top chaos player (any player, it may be a
	 * host Night City answers instead). Patrols only when nobody is chased; chases only players with chaos (all of them with
	 * {@code everyone}); Max-Tac only on the top player and only when it may be hunted.
	 */
	public static List<Order> plan(final @Nullable HeatTier tier, final List<Ranked> ranked, final @Nullable UUID top) {
		List<Order> out = new ArrayList<>();
		if (tier == null) {
			return out;
		}

		HeatTier.Response r = tier.response();
		List<UUID> chased = new ArrayList<>();
		for (Ranked p : ranked) {
			if (r.everyone() || p.chaos() > 0.0 && chased.size() < r.chase()) {
				chased.add(p.uuid());
			}
		}

		for (UUID u : chased) {
			add(out, u, Unit.OFFICER, r.officers());
			add(out, u, Unit.HEAVY, r.heavy());
			add(out, u, Unit.DRONE, r.drones());
		}

		if (r.maxtac() > 0 && top != null && ranked.stream().anyMatch(p -> p.uuid().equals(top))) {
			add(out, top, Unit.MAXTAC, r.maxtac());
		}

		if (chased.isEmpty() && r.patrol() > 0 && !ranked.isEmpty()) {
			add(out, ranked.getFirst().uuid(), Unit.PATROL, r.patrol());
		}

		return out;
	}

	private static void add(final List<Order> out, final UUID target, final Unit unit, final int count) {
		if (count > 0) {
			out.add(new Order(target, unit, count));
		}
	}

	/** A player squads may hunt, with its recent chaos. */
	public record Ranked(UUID uuid, double chaos) {
	}
}
