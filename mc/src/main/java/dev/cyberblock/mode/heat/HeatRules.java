package dev.cyberblock.mode.heat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the Server Heat meter (megaplan 8-T1), pure so the tests can drive them: what each kind of chaos adds, the gain cap,
 * the decay, the tier of a value (with the tiers' enter/exit hysteresis), the wanted level a host gets, and the squads a tier asks
 * for. {@link HeatMeter} and {@link ChaosBoard} hold the state; {@link ServerHeat} wires them to the server.
 */
public final class HeatRules {
	public static final double MAX = 100.0;
	/** The meter gains at most this many points per second, whatever happens (a TNT chain cannot jump it from 0 to 100 at once). */
	public static final double GAIN_PER_SECOND = 15.0;
	/** ...with this much saved up for a burst. */
	public static final double GAIN_BURST = 30.0;
	/** Below tier 1: no decay until this long after the last chaos (server ticks: 10 s); a tier has its own {@code grace_s}. */
	public static final long CALM_GRACE_TICKS = 200;
	/** Points per second lost below tier 1. */
	public static final double CALM_DECAY = 1.0;
	/** A {@code loudest} tier chases at most this many players (the loudest first). */
	public static final int LOUDEST = 2;

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

	private static HeatTier builtIn(final int tier, final String name, final int enter, final int exit, final double decay, final int grace, final int wanted,
		final int topWanted, final HeatTier.Target target, final int maxAlive, final HeatTier.Wave... waves) {
		return new HeatTier(tier, name, enter, exit, decay, grace, wanted, topWanted, new HeatTier.Response(target, maxAlive, List.of(waves)));
	}

	private static HeatTier.Wave wave(final String entry, final int every) {
		return new HeatTier.Wave("cyberblock:" + entry, every);
	}

	/** The built-in tiers (used while no {@code heat_tier} content is loaded): the same numbers as 8A's default files. */
	public static final List<HeatTier> BUILT_IN = List.of(
		builtIn(1, "Noticed", 15, 10, 0.4, 10, 1, -1, HeatTier.Target.AREA, 4, wave("ncpd_patrol", 90)),
		builtIn(2, "Pursuit", 35, 30, 0.35, 15, 2, -1, HeatTier.Target.LOUDEST, 6, wave("ncpd_patrol", 45)),
		builtIn(3, "Heavy Response", 55, 50, 0.3, 20, 3, -1, HeatTier.Target.LOUDEST, 8, wave("ncpd_enforcers", 60), wave("wyvern_drones", 90)),
		builtIn(4, "Max-Tac Hunt", 75, 70, 0.25, 25, 4, 5, HeatTier.Target.TOP_CHAOS, 8, wave("maxtac_squad", 120), wave("ncpd_enforcers", 60)),
		builtIn(5, "Citywide Lockdown", 92, 88, 0.2, 30, 5, -1, HeatTier.Target.EVERYONE, 12, wave("maxtac_squad", 90), wave("wyvern_drones", 60),
			wave("ncpd_enforcers", 45)));

	private HeatRules() {
	}

	/** The meter after adding {@code amount} (clamped to 0..100). */
	public static double feed(final double value, final double amount) {
		return Math.clamp(value + (Double.isFinite(amount) ? amount : 0.0), 0.0, MAX);
	}

	/** The tiers by tier number. */
	public static List<HeatTier> sorted(final List<HeatTier> tiers) {
		List<HeatTier> out = new ArrayList<>(tiers);
		out.sort(Comparator.comparingInt(HeatTier::tier));
		return out;
	}

	/** The tier with this number, or null (tier 0, or a number the tiers lack). */
	public static @Nullable HeatTier byNumber(final int tier, final List<HeatTier> sorted) {
		for (HeatTier t : sorted) {
			if (t.tier() == tier) {
				return t;
			}
		}

		return null;
	}

	/**
	 * The tier number 0..5 after the meter moved to {@code value} while the tier was {@code current}: up to the highest tier whose
	 * {@code enter} it reached; otherwise down, tier by tier, while it is below the tier's {@code exit} (so a meter sitting on a
	 * boundary does not flicker between two tiers).
	 */
	public static int next(final double value, final int current, final List<HeatTier> sorted) {
		int reached = 0;
		for (HeatTier t : sorted) {
			if (value >= t.enter()) {
				reached = Math.max(reached, t.tier());
			}
		}

		HeatTier at = byNumber(current, sorted);
		if (reached >= current || at == null) {
			return reached; // a rise, or a tier that is no longer loaded: start over from the value
		}

		int t = current;
		while (at != null && value < at.exit()) {
			HeatTier below = null;
			for (HeatTier h : sorted) {
				if (h.tier() < t && (below == null || h.tier() > below.tier())) {
					below = h;
				}
			}

			t = below == null ? 0 : below.tier();
			at = below;
		}

		return t;
	}

	/**
	 * The meter after {@code seconds} of cooling at tier {@code current}: nothing within its grace time of the last chaos, then its
	 * decay ({@link #CALM_GRACE_TICKS} and {@link #CALM_DECAY} below tier 1). Never below 0.
	 */
	public static double decay(final double value, final double seconds, final long ticksSinceChaos, final @Nullable HeatTier current) {
		long grace = current == null ? CALM_GRACE_TICKS : current.graceSeconds() * 20L;
		if (ticksSinceChaos < grace || value <= 0.0) {
			return Math.max(0.0, value);
		}

		double rate = current == null ? CALM_DECAY : current.decayPerSecond();
		return Math.max(0.0, value - rate * seconds);
	}

	/**
	 * The real wanted level for a host (Phase 0 e): the tier's {@code host_wanted}; the top chaos player gets {@code top_chaos_wanted}
	 * when the tier has one (tier 4: Night City's own Max-Tac). 0 below tier 1.
	 */
	public static int wanted(final @Nullable HeatTier tier, final boolean topChaos) {
		if (tier == null) {
			return 0;
		}

		int w = topChaos && tier.topChaosWanted() >= 0 ? tier.topChaosWanted() : tier.hostWanted();
		return Math.clamp(w, 0, 5);
	}

	/**
	 * Is this player answered by Minecraft squads like a guest? Yes unless it has a session whose Cyberpunk is active (On and
	 * streaming): a linked host with Cyberpunk Off plays Minecraft and gets no real wanted level.
	 */
	public static boolean minecraftOnly(final boolean session, final boolean active) {
		return !session || !active;
	}

	/** A Minecraft squad unit. */
	public enum Unit {
		OFFICER, HEAVY, DRONE, MAXTAC
	}

	/** The Minecraft squad of a {@code spawn_palette} entry: which unit and how many come per wave. */
	public record Squad(Unit unit, int count) {
	}

	/**
	 * The squad a wave's {@code spawn_palette} entry sends in the Minecraft world: 8A's law entries by id (the counts of their
	 * {@code squad}), any other entry by the words in its id (maxtac, drone, enforcer or heavy; else NCPD officers), two per wave.
	 * The palette itself is 8O's kind; after the merge the integrator may resolve entries through it instead.
	 */
	public static Squad squad(final String entry) {
		return switch (entry) {
			case "cyberblock:ncpd_patrol" -> new Squad(Unit.OFFICER, 2);
			case "cyberblock:ncpd_enforcers" -> new Squad(Unit.HEAVY, 2);
			case "cyberblock:wyvern_drones" -> new Squad(Unit.DRONE, 2);
			case "cyberblock:octant_drone" -> new Squad(Unit.DRONE, 1);
			case "cyberblock:maxtac_squad" -> new Squad(Unit.MAXTAC, 2);
			default -> {
				String path = entry.substring(entry.indexOf(':') + 1).toLowerCase(Locale.ROOT);
				Unit unit = path.contains("maxtac") || path.contains("max_tac") ? Unit.MAXTAC : path.contains("drone") ? Unit.DRONE
					: path.contains("enforcer") || path.contains("heavy") ? Unit.HEAVY : Unit.OFFICER;
				yield new Squad(unit, 2);
			}
		};
	}

	/**
	 * One wave of one tier sent after (or, when not {@code chase}, patrolling around) one player: {@code count} units of a kind every
	 * {@code everyTicks}. {@code wave} is the wave's index in the tier (its timer).
	 */
	public record Order(UUID target, int wave, Unit unit, int count, long everyTicks, boolean chase) {
	}

	/**
	 * The squads a tier asks for. {@code ranked}: the players squads may hunt (Minecraft-only players, and hosts whose real wanted level
	 * did not follow), loudest first, each with its recent chaos; {@code top} the server's top chaos player (any player: it may be a
	 * host Night City answers instead). By the tier's {@code target}: area = patrols near every one of them (no chase), loudest = the
	 * {@link #LOUDEST} players with chaos, top_chaos = only the top player and only when squads may hunt it, everyone = all of them.
	 * Every target gets every wave.
	 */
	public static List<Order> plan(final @Nullable HeatTier tier, final List<Ranked> ranked, final @Nullable UUID top) {
		List<Order> out = new ArrayList<>();
		if (tier == null) {
			return out;
		}

		HeatTier.Response r = tier.response();
		List<UUID> targets = switch (r.target()) {
			case AREA, EVERYONE -> ranked.stream().map(Ranked::uuid).toList();
			case LOUDEST -> ranked.stream().filter(p -> p.chaos() > 0.0).limit(LOUDEST).map(Ranked::uuid).toList();
			case TOP_CHAOS -> top != null && ranked.stream().anyMatch(p -> p.uuid().equals(top)) ? List.of(top) : List.of();
		};
		for (UUID u : targets) {
			for (int i = 0; i < r.waves().size(); i++) {
				HeatTier.Wave w = r.waves().get(i);
				Squad s = squad(w.entry());
				out.add(new Order(u, i, s.unit(), s.count(), w.everySeconds() * 20L, r.target() != HeatTier.Target.AREA));
			}
		}

		return out;
	}

	/** A player squads may hunt, with its recent chaos. */
	public record Ranked(UUID uuid, double chaos) {
	}
}
