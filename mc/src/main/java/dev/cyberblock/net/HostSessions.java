package dev.cyberblock.net;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Every host session of the server (megaplan section 1.4), keyed by player UUID. Multi-host by design; ServerConfig.maxHosts caps
 * how many hold the host role (1 until Phase 3). UP messages that passed the relay guard arrive in {@link #receive}: the session's
 * own fields (hello, hstate, state, aim, stats) are applied first, then the listeners of that type run in registration order (one
 * that throws is logged and the rest still run). Server thread only (reads from other threads see a consistent map but may be
 * stale).
 */
public final class HostSessions {
	private static final Map<UUID, HostSession> sessions = new ConcurrentHashMap<>();
	private static final Map<String, List<BiConsumer<HostSession, JsonObject>>> listeners = new ConcurrentHashMap<>();
	private static final List<Consumer<HostSession>> starts = new CopyOnWriteArrayList<>();
	private static final List<Consumer<HostSession>> ends = new CopyOnWriteArrayList<>();
	/** Orders activations: the earliest active session is the primary. */
	private static long sequence;
	private static volatile boolean rosterDirty;

	private HostSessions() {
	}

	public static @Nullable HostSession of(final UUID player) {
		return sessions.get(player);
	}

	public static @Nullable HostSession of(final ServerPlayer player) {
		return sessions.get(player.getUUID());
	}

	/** Every session that is LINKED or ACTIVE (a granted host role whose DLL has said hello). */
	public static Collection<HostSession> all() {
		return sessions.values().stream().filter(s -> s.state() != HostSession.State.NONE).toList();
	}

	/** Every ACTIVE session (hstate.active). */
	public static Collection<HostSession> active() {
		return sessions.values().stream().filter(HostSession::active).toList();
	}

	/** The earliest active session (it drives server-wide time of day and weather from Phase 3), or null. */
	public static @Nullable HostSession primary() {
		return sessions.values().stream().filter(HostSession::active).min(Comparator.comparingLong(HostSession::activeSince)).orElse(null);
	}

	/** The active session whose player is nearest to pos in that level, within radius; null when none. */
	public static @Nullable HostSession nearestActive(final ServerLevel level, final Vec3 pos, final double radius) {
		HostSession best = null;
		double bestSq = radius * radius;
		for (HostSession s : sessions.values()) {
			ServerPlayer p = s.active() ? s.player() : null;
			if (p != null && p.level() == level) {
				double d = p.position().distanceToSqr(pos);
				if (d <= bestSq) {
					bestSq = d;
					best = s;
				}
			}
		}

		return best;
	}

	/** Adds a listener for UP messages of this type (server thread, registration order). */
	public static void listen(final String t, final BiConsumer<HostSession, JsonObject> handler) {
		listeners.computeIfAbsent(t, k -> new CopyOnWriteArrayList<>()).add(handler);
	}

	/** After each host hello (the resync point: align, blocks reset, effects, perks...). */
	public static void onStart(final Consumer<HostSession> l) {
		starts.add(l);
	}

	/** When a session ends: disconnect, DLL link lost, role revoked, overflow kick, server stop. Called once per session. */
	public static void onEnd(final Consumer<HostSession> l) {
		ends.add(l);
	}

	/** The player holds the host role (a session in any state). */
	public static boolean isHost(final UUID player) {
		return sessions.containsKey(player);
	}

	/** How many players other than this one hold the host role (for maxHosts). */
	static int hostsOtherThan(final UUID player) {
		int n = 0;
		for (UUID u : sessions.keySet()) {
			if (!u.equals(player)) {
				n++;
			}
		}

		return n;
	}

	/** The handshake granted the host role: the player's session (a new one in state NONE, or the one it already has). */
	static HostSession grant(final UUID uuid, final String name, final @Nullable MinecraftServer server, final Consumer<String> wire) {
		return sessions.computeIfAbsent(uuid, u -> new HostSession(u, name, server, wire));
	}

	/** Ends the player's session, if any: the end listeners run once, then the line `session: <name> ended (<reason>)`. */
	static void end(final UUID uuid, final String reason) {
		HostSession s = sessions.remove(uuid);
		if (s == null) {
			return;
		}

		for (Consumer<HostSession> l : ends) {
			try {
				l.accept(s);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("session end listener failed for {}: {}", s.name(), e.toString());
			}
		}

		Permissions.store().setLastSession(uuid, System.currentTimeMillis());
		rosterDirty = true;
		Cyberblock.LOG.info("session: {} ended ({})", s.name(), reason);
	}

	/** Server stop (or tests): every session ends. */
	static void endAll(final String reason) {
		for (UUID u : List.copyOf(sessions.keySet())) {
			end(u, reason);
		}
	}

	/**
	 * One UP message that passed the relay guard. The session's own fields first, then the listeners. Returns false when the message
	 * was invalid for its type (nothing applied, no listener ran).
	 */
	static boolean receive(final HostSession s, final String t, final JsonObject m) {
		HostSession.State change = null;
		boolean started = false;
		try {
			switch (t) {
				case "hello" -> {
					if (!s.applyHello(m)) {
						Cyberblock.LOG.warn("session: {} hello of another protocol ignored", s.name());
						return false;
					}

					started = true;
					change = s.reactivate(++sequence);
				}
				case "hstate" -> change = s.applyHstate(m, ++sequence);
				case "state" -> change = s.applyState(m, ++sequence);
				case "aim" -> s.applyAim(m);
				case "stats" -> s.applyStats(m);
				default -> {
				}
			}
		} catch (IllegalArgumentException e) {
			Cyberblock.LOG.debug("session: {} bad {}: {}", s.name(), t, e.getMessage());
			return false;
		}

		if (started) {
			rosterDirty = true;
			Cyberblock.LOG.info("session: {} started", s.name());
			for (Consumer<HostSession> l : starts) {
				try {
					l.accept(s);
				} catch (RuntimeException e) {
					Cyberblock.LOG.warn("session start listener failed for {}: {}", s.name(), e.toString());
				}
			}
		}

		if (change == HostSession.State.ACTIVE) {
			Cyberblock.LOG.info("session: {} active", s.name());
		}

		dispatch(s, t, m);
		return true;
	}

	private static void dispatch(final HostSession s, final String t, final JsonObject m) {
		List<BiConsumer<HostSession, JsonObject>> list = listeners.get(t);
		if (list == null) {
			return;
		}

		for (BiConsumer<HostSession, JsonObject> l : list) {
			try {
				l.accept(s, m);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("listener for {} failed: {}", t, e.toString());
			}
		}
	}

	/** End of the server tick: every session's DOWN queue goes out. */
	static void flushAll() {
		for (HostSession s : sessions.values()) {
			s.flush();
		}
	}

	/** Something the roster shows changed (a join, a leave, a session start or end; later phases: body, team, pvp, downed). */
	public static void rosterChanged() {
		rosterDirty = true;
	}

	/** True once after each change. */
	static boolean takeRosterDirty() {
		boolean d = rosterDirty;
		rosterDirty = false;
		return d;
	}

	static Collection<HostSession> sessionsInAnyState() {
		return new ArrayList<>(sessions.values());
	}

	/** Tests: forget every session and listener. */
	static void reset() {
		sessions.clear();
		listeners.clear();
		starts.clear();
		ends.clear();
		sequence = 0;
		rosterDirty = false;
	}
}
