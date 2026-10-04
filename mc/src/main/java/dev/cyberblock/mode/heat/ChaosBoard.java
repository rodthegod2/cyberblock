package dev.cyberblock.mode.heat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Who has been loud lately (megaplan 8-T1: "NCPD chases the loudest players", "Max-Tac hunts the top chaos player"): each player's
 * recent chaos, halving every {@link #HALF_LIFE_SECONDS}; below {@link #FORGET} a player is forgotten. Server thread.
 */
public final class ChaosBoard {
	public static final double HALF_LIFE_SECONDS = 120.0;
	static final double FORGET = 0.05;
	private final Map<UUID, Double> recent = new HashMap<>();

	public void add(final UUID player, final double amount) {
		if (amount > 0.0 && Double.isFinite(amount)) {
			this.recent.merge(player, amount, Double::sum);
		}
	}

	/** Lets {@code seconds} pass. */
	public void decay(final double seconds) {
		double f = Math.pow(0.5, seconds / HALF_LIFE_SECONDS);
		this.recent.replaceAll((u, v) -> v * f);
		this.recent.values().removeIf(v -> v < FORGET);
	}

	public double of(final UUID player) {
		return this.recent.getOrDefault(player, 0.0);
	}

	/** The loudest player (ties by UUID, so the answer is stable), or null when nobody has chaos. */
	public @Nullable UUID top() {
		List<HeatRules.Ranked> r = this.ranked(u -> true);
		return r.isEmpty() ? null : r.getFirst().uuid();
	}

	/** The players {@code eligible} accepts that have chaos, loudest first. */
	public List<HeatRules.Ranked> ranked(final Predicate<UUID> eligible) {
		List<HeatRules.Ranked> out = new ArrayList<>();
		this.recent.forEach((u, v) -> {
			if (eligible.test(u)) {
				out.add(new HeatRules.Ranked(u, v));
			}
		});
		out.sort(Comparator.comparingDouble(HeatRules.Ranked::chaos).reversed().thenComparing(HeatRules.Ranked::uuid));
		return out;
	}

	public void forget(final UUID player) {
		this.recent.remove(player);
	}

	public void clear() {
		this.recent.clear();
	}
}
