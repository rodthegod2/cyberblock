package dev.cyberblock.client.holo;

import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.holo.HoloWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The Holo-Table's spawn list (pure, tested): the server's spawn points and 7V's fast-travel points, grouped and sorted. */
final class HoloEntries {
	static final List<String> GROUPS = List.of("landmark", "beacon", "base", "friend", "travel");

	/** One line: its group, a key that survives new states (kind:id, travel:name), what to show, where, and what it does. */
	record Entry(String group, String key, String name, String sub, double x, double y, double z, boolean ok, String why, HoloRules.@Nullable Kind kind,
		String id, HoloFastTravel.@Nullable Point point) {
	}

	private HoloEntries() {
	}

	/** Grouped landmarks, beacons, team bases, friends, then the host's fast-travel points; by name inside a group. */
	static List<Entry> of(final HoloWire.@Nullable State s, final List<HoloFastTravel.Point> travel) {
		List<Entry> out = new ArrayList<>();
		if (s != null) {
			for (HoloWire.SpawnPoint p : s.spawns()) {
				HoloRules.Kind kind = HoloRules.Kind.of(p.kind());
				String sub = kind == HoloRules.Kind.LANDMARK ? p.district() : kind == HoloRules.Kind.BEACON ? p.owner() : "";
				out.add(new Entry(p.kind(), p.kind() + ":" + p.id(), p.name(), sub, p.x(), p.y(), p.z(), p.ok(), p.why(), kind, p.id(), null));
			}
		}

		for (HoloFastTravel.Point p : travel) {
			out.add(new Entry("travel", "travel:" + p.name(), p.name(), p.district(), p.x(), 0, p.z(), true, "", null, "", p));
		}

		out.sort(Comparator.comparingInt((Entry e) -> GROUPS.indexOf(e.group())).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
		return out;
	}

	/** The entry with this key, or null. */
	static @Nullable Entry find(final List<Entry> entries, final @Nullable String key) {
		for (Entry e : entries) {
			if (e.key().equals(key)) {
				return e;
			}
		}

		return null;
	}
}
