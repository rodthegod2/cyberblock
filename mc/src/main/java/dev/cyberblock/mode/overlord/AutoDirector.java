package dev.cyberblock.mode.overlord;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rule-based Auto-Director (megaplan 8-O1): the Overlord of a match with no human Overlord online. Pure; the server asks it every
 * few seconds and places what it picks through the same fairness rules and warning as a human's request.
 *
 * <p>The rules, by match pace (0 calm .. 1 everyone is fighting) and Server Heat (0..100):
 * <ul>
 * <li>It waits between its actions: 8 s in a calm match, up to 30 s in a hot one.</li>
 * <li>A hot match (pace above 0.75) gets a breather: nothing, unless the Threat Points are full.</li>
 * <li>Heat picks who comes: from Heat 40 the law (NCPD) is preferred, Max-Tac only from Heat 80; below 40 gangs, machines and
 * monsters.</li>
 * <li>A calm match gets the dearest affordable entry it may afford with most of its points; a busier one keeps more back.</li>
 * <li>From Heat 40, one decision in four may be an event (blackout, EMP, airstrike, traffic jam, outbreak) instead.</li>
 * </ul>
 */
public final class AutoDirector {
	public static final double MIN_GAP_S = 8.0, MAX_GAP_S = 30.0;
	public static final double HOT_PACE = 0.75;
	public static final int LAW_HEAT = 40, MAXTAC_HEAT = 80, EVENT_HEAT = 40;
	public static final double EVENT_CHANCE = 0.25;
	/** The events the director may use (never weather or a supply drop: those are a human Overlord's call). */
	public static final Set<String> DIRECTOR_EVENTS = Set.of("blackout", "emp", "airstrike", "traffic_jam", "outbreak");

	/** A palette entry as the director sees it: ready = off its cooldown. */
	public record Spawn(String id, String group, @Nullable String rec, int cost, int units, boolean ready) {
	}

	/** An event as the director sees it. */
	public record Event(String id, String type, int cost, boolean ready) {
	}

	public record Input(double points, Intensity intensity, int live, int heat, double pace, double sinceLastS, List<Spawn> spawns, List<Event> events,
		double roll) {
	}

	/** What to do: a spawn or an event by id. */
	public record Choice(boolean event, String id) {
	}

	private AutoDirector() {
	}

	/** Seconds the director waits after its last action at this pace. */
	public static double gap(final double pace) {
		return MIN_GAP_S + (MAX_GAP_S - MIN_GAP_S) * clamp(pace);
	}

	/** May the director send this entry at this Heat? */
	static boolean allowed(final Spawn s, final int heat) {
		if ("maxtac".equals(s.rec())) {
			return heat >= MAXTAC_HEAT;
		}

		return !s.group().equals("law") || heat >= LAW_HEAT;
	}

	/** Null: wait. */
	public static @Nullable Choice decide(final Input in) {
		double pace = clamp(in.pace());
		if (in.sinceLastS() < gap(pace)) {
			return null;
		}

		boolean full = in.points() >= in.intensity().maxPoints() - 1;
		if (pace > HOT_PACE && !full) {
			return null;
		}

		if (in.heat() >= EVENT_HEAT && in.roll() < EVENT_CHANCE) {
			Event e = in.events().stream().filter(x -> x.ready() && x.cost() <= in.points() && DIRECTOR_EVENTS.contains(x.type()))
				.max(Comparator.comparingInt(Event::cost).thenComparing(Event::id, Comparator.reverseOrder())).orElse(null);
			if (e != null) {
				return new Choice(true, e.id());
			}
		}

		int room = in.intensity().cap() - in.live();
		List<Spawn> usable = in.spawns().stream()
			.filter(s -> s.ready() && s.cost() <= in.points() && s.units() <= room && allowed(s, in.heat())).toList();
		if (usable.isEmpty()) {
			return null;
		}

		// Heat decides who comes: the law when it is high and the law is available, else everyone else
		boolean law = in.heat() >= LAW_HEAT && usable.stream().anyMatch(s -> s.group().equals("law"));
		List<Spawn> pool = usable.stream().filter(s -> s.group().equals("law") == law).toList();
		// a calm match may spend up to all of its points on one spawn, a busier one keeps half back
		double budget = in.points() * (1.0 - 0.5 * pace);
		Comparator<Spawn> dearest = Comparator.comparingInt(Spawn::cost).thenComparing(Spawn::id, Comparator.reverseOrder());
		Spawn pick = pool.stream().filter(s -> s.cost() <= budget).max(dearest)
			.orElseGet(() -> pool.stream().min(Comparator.comparingInt(Spawn::cost).thenComparing(Spawn::id)).orElseThrow());
		return new Choice(false, pick.id());
	}

	static double clamp(final double v) {
		return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0;
	}
}
