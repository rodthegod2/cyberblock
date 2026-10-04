package dev.cyberblock.mode.overlord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The runtime check of Phase 0 (d) per host (megaplan 8-O1: Night City enemies through {@code spawn} DOWN; Minecraft squads
 * otherwise). The in-game CET check of (d) has not run yet, so each host's game is asked, and its answer decides: the first
 * {@code spawnresult ok} marks the host's Night City spawns as working; no answer within 5 s, or {@code ok:false} with reason
 * {@code unsupported}, marks them as not working until that host's next session, and every spawn waiting on it becomes its
 * Minecraft squad instead (the written fallback). Any other refusal ({@code no room}, {@code no navmesh}...) falls back for that one
 * spawn only. One log line says which path is active for each host. Server thread.
 */
final class NcSpawns {
	static final long ANSWER_MS = 5_000L;

	/**
	 * The {@code spawn.rec} on the wire (shared/protocol.md "Phase 8"): a heavy unit is its own record, never a flag, so a
	 * {@code spawn_palette} entry's {@code "heavy": true} on {@code ncpd} sends {@code ncpd_heavy}; every other record goes as written.
	 */
	static String wireRec(final String rec, final boolean heavy) {
		return heavy && "ncpd".equals(rec) ? "ncpd_heavy" : rec;
	}

	enum Mode {
		UNKNOWN, WORKS, FALLBACK
	}

	private record Wait(UUID host, int id, long deadline, Runnable fallback, Consumer<Integer> placed) {
	}

	private final Map<UUID, Mode> modes = new HashMap<>();
	private final List<Wait> waits = new ArrayList<>();
	private final Consumer<String> log;

	NcSpawns(final Consumer<String> log) {
		this.log = log;
	}

	Mode mode(final UUID host) {
		return this.modes.getOrDefault(host, Mode.UNKNOWN);
	}

	/** May a Night City spawn be tried in this host's game now? */
	boolean usable(final UUID host) {
		return this.mode(host) != Mode.FALLBACK;
	}

	/**
	 * A {@code spawn} with request id {@code id} went DOWN to {@code host}: {@code placed} gets the count on {@code ok}, {@code fallback}
	 * runs instead when the host refuses or does not answer.
	 */
	void sent(final UUID host, final String name, final int id, final long now, final Runnable fallback, final Consumer<Integer> placed) {
		if (this.mode(host) == Mode.UNKNOWN && this.waits.stream().noneMatch(w -> w.host().equals(host))) {
			this.log.accept("overlord: Night City spawns for " + name + ": trying spawn DOWN (Phase 0 d not confirmed in game yet; waiting for spawnresult)");
		}

		this.waits.add(new Wait(host, id, now + ANSWER_MS, fallback, placed));
	}

	/** The host's {@code spawnresult}; false when no spawn waits on that id (a stale or foreign answer). */
	boolean answered(final UUID host, final String name, final int id, final boolean ok, final int n, final String reason) {
		Wait w = this.take(host, id);
		if (w == null) {
			return false;
		}

		if (ok) {
			if (this.modes.put(host, Mode.WORKS) != Mode.WORKS) {
				this.log.accept("overlord: Night City spawns for " + name + ": spawn DOWN active (spawnresult ok)");
			}

			w.placed().accept(n);
			return true;
		}

		if (reason.equals("unsupported")) {
			this.fallBack(host, name, "the game answered unsupported");
		}

		w.fallback().run();
		return true;
	}

	/** Every spawn whose answer is overdue: that host falls back to Minecraft squads (with one log line). */
	void tick(final long now, final java.util.function.Function<UUID, String> names) {
		List<Wait> due = new ArrayList<>();
		for (Iterator<Wait> it = this.waits.iterator(); it.hasNext();) {
			Wait w = it.next();
			if (now >= w.deadline()) {
				it.remove();
				due.add(w);
			}
		}

		for (Wait w : due) {
			if (this.mode(w.host()) != Mode.WORKS) {
				this.fallBack(w.host(), names.apply(w.host()), "no spawnresult in " + ANSWER_MS / 1000 + " s");
			}

			w.fallback().run();
		}
	}

	/** The host's session started again or ended: ask its game afresh next time; its waiting spawns are dropped. */
	void reset(final UUID host) {
		this.modes.remove(host);
		this.waits.removeIf(w -> w.host().equals(host));
	}

	int waiting() {
		return this.waits.size();
	}

	private void fallBack(final UUID host, final String name, final String why) {
		if (this.modes.put(host, Mode.FALLBACK) != Mode.FALLBACK) {
			this.log.accept("overlord: Night City spawns for " + name + ": " + why + " -> Minecraft squads (fallback)");
		}
	}

	private @Nullable Wait take(final UUID host, final int id) {
		for (Iterator<Wait> it = this.waits.iterator(); it.hasNext();) {
			Wait w = it.next();
			if (w.host().equals(host) && w.id() == id) {
				it.remove();
				return w;
			}
		}

		return null;
	}
}
