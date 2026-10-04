package dev.cyberblock.hack.actions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who watches each host's photo feed (megaplan tasks 6-I1 and 6-D1: the DLL sends `feed` only while requested): a Net Map viewer
 * renews its watch every few seconds, a player holding the host's feed map counts while he holds it. A host's `feedreq` goes on
 * when its first viewer comes and off when its last one is gone for 5 s; after a host hello (the DLL forgot) it is asked again.
 * Pure (the clock is passed in).
 */
final class FeedViewers {
	/** A watch lasts this long unless renewed. */
	static final long WATCH_MS = 5_000L;

	/** One feedreq to send: on or off for this host. */
	record Change(UUID host, boolean on) {
	}

	/** host -> viewer -> until (ms); `screen` viewers get the frames, map holders see the map item. */
	private final Map<UUID, Map<UUID, Long>> screens = new HashMap<>();
	private final Map<UUID, Map<UUID, Long>> maps = new HashMap<>();
	/** Hosts whose last feedreq was on, and when it went on (ms). */
	private final Map<UUID, Long> on = new HashMap<>();

	/** A Net Map viewer starts, renews or stops watching. */
	void watch(final UUID host, final UUID viewer, final boolean watching, final long now) {
		mark(this.screens, host, viewer, watching, now);
	}

	/** A player holds this host's feed map now. */
	void holding(final UUID host, final UUID viewer, final long now) {
		mark(this.maps, host, viewer, true, now);
	}

	private static void mark(final Map<UUID, Map<UUID, Long>> by, final UUID host, final UUID viewer, final boolean watching, final long now) {
		if (watching) {
			by.computeIfAbsent(host, k -> new HashMap<>()).put(viewer, now + WATCH_MS);
		} else {
			by.computeIfPresent(host, (k, m) -> {
				m.remove(viewer);
				return m.isEmpty() ? null : m;
			});
		}
	}

	/** The Net Map viewers of this host now (they get each frame). */
	Set<UUID> screenViewers(final UUID host, final long now) {
		return live(this.screens, host, now);
	}

	private static Set<UUID> live(final Map<UUID, Map<UUID, Long>> by, final UUID host, final long now) {
		Map<UUID, Long> m = by.get(host);
		if (m == null) {
			return Set.of();
		}

		m.values().removeIf(until -> until < now);
		if (m.isEmpty()) {
			by.remove(host); // no host key outlives its last viewer
		}

		return Set.copyOf(m.keySet());
	}

	/** Hosts with any entry (tests: nothing is kept for hosts nobody watches). */
	int hostsKept() {
		return union().size();
	}

	/** The feedreq messages due now: on for a host that has viewers and is not on, off for one that is on and has none. */
	List<Change> changes(final long now) {
		Set<UUID> wanted = new HashSet<>();
		for (UUID host : union()) {
			if (!live(this.screens, host, now).isEmpty() || !live(this.maps, host, now).isEmpty()) {
				wanted.add(host);
			}
		}

		List<Change> out = new ArrayList<>();
		for (UUID host : wanted) {
			if (this.on.putIfAbsent(host, now) == null) {
				out.add(new Change(host, true));
			}
		}

		for (UUID host : Set.copyOf(this.on.keySet())) {
			if (!wanted.contains(host)) {
				this.on.remove(host);
				out.add(new Change(host, false));
			}
		}

		return out;
	}

	private Set<UUID> union() {
		Set<UUID> all = new HashSet<>(this.screens.keySet());
		all.addAll(this.maps.keySet());
		all.addAll(this.on.keySet());
		return all;
	}

	/** The hosts whose feedreq is on now, each with when it went on (ms). */
	Map<UUID, Long> requested() {
		return Map.copyOf(this.on);
	}

	/** The host's DLL said hello again: it forgot the request, so the next changes() asks again when someone watches. */
	void restarted(final UUID host) {
		this.on.remove(host);
	}

	/** The host's session ended: nothing to ask it any more. */
	void ended(final UUID host) {
		this.on.remove(host);
		this.screens.remove(host);
		this.maps.remove(host);
	}

	/** A player left: he watches nothing. */
	void left(final UUID viewer) {
		for (Map<UUID, Map<UUID, Long>> by : List.of(this.screens, this.maps)) {
			by.values().forEach(m -> m.remove(viewer));
			by.values().removeIf(Map::isEmpty);
		}
	}

	void clear() {
		this.screens.clear();
		this.maps.clear();
		this.on.clear();
	}
}
