package dev.cyberblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.world.phys.Vec3;

/**
 * The host's {"t":"npcs","list":[...]} as validated entries, and what to do about the proxies that exist: spawn, move,
 * remove. Pure logic (no Minecraft world), so it can be tested; ProxyManager carries the plan out on the server thread.
 */
public final class ProxyDiff {
	public enum Kind {
		HUMAN, VEHICLE
	}

	/** One Cyberpunk NPC or vehicle: id (the unsigned 64 bit entity id hash), feet position, facing, and whether it is hostile to V. */
	public record Entry(long id, double x, double y, double z, float yaw, Kind kind, boolean hostile) {
	}

	public record Plan(List<Entry> spawn, List<Entry> move, List<Long> remove) {
	}

	/**
	 * Where the host last put one NPC or vehicle, how fast it was going by the last two lists (m/s), and when (System.nanoTime). The host
	 * lists 10 times a second, a car at 15 m/s would otherwise jump 1.5 m at a time: the proxy carries on at that speed in between, so
	 * what Minecraft aims at is where the car is now.
	 */
	public record Motion(double x, double y, double z, double vx, double vy, double vz, long nanos) {
		/** Faster than this between two lists is a teleport (a respawn, a fast travel), not driving: the proxy only jumps. */
		static final double MAX_SPEED = 40.0;
		/** The proxy never runs on further than this (s) ahead of the last list: a host gone quiet leaves it where it was heading. */
		static final double MAX_EXTRAPOLATION = 0.5;
		/** Two lists closer than this (s) say nothing about the speed. */
		static final double MIN_INTERVAL = 0.02;

		/** The first sighting: standing still until the next list says otherwise. */
		public static Motion first(final Entry e, final long nanos) {
			return new Motion(e.x(), e.y(), e.z(), 0.0, 0.0, 0.0, nanos);
		}

		/** The host's next list (received at nanos) put it at e: the speed it took to get there, or none for a teleport. */
		public Motion next(final Entry e, final long nanos) {
			double dt = (nanos - this.nanos) / 1.0E9;
			if (!(dt >= MIN_INTERVAL)) {
				return new Motion(e.x(), e.y(), e.z(), this.vx, this.vy, this.vz, nanos);
			}

			double nvx = (e.x() - this.x) / dt, nvy = (e.y() - this.y) / dt, nvz = (e.z() - this.z) / dt;
			boolean drives = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz) <= MAX_SPEED;
			return new Motion(e.x(), e.y(), e.z(), drives ? nvx : 0.0, drives ? nvy : 0.0, drives ? nvz : 0.0, nanos);
		}

		/** Where it is by now: the last list's position plus the speed times the time since, at most MAX_EXTRAPOLATION of it. */
		public Vec3 position(final long nanos) {
			double t = Math.clamp((nanos - this.nanos) / 1.0E9, 0.0, MAX_EXTRAPOLATION);
			return new Vec3(this.x + this.vx * t, this.y + this.vy * t, this.z + this.vz * t);
		}

		public boolean moving() {
			return this.vx != 0.0 || this.vy != 0.0 || this.vz != 0.0;
		}
	}

	/** The usable entries of a message and how many were thrown out. */
	public record Parsed(List<Entry> entries, int rejected) {
	}

	/** The host sends at most 48; more than this in one message is a bug or an attack. */
	static final int MAX_ENTRIES = 128;
	/** Positions beyond these are garbage, not a place in the world. */
	private static final double XZ_LIMIT = 2.9E7;
	private static final Pattern DECIMAL_ID = Pattern.compile("[0-9]{1,20}");

	private ProxyDiff() {
	}

	/**
	 * The entries of a message, or null when it has no list at all (then nothing changes: an empty list means "nobody around",
	 * a missing one is a broken message). Entries that are malformed, out of range, repeated or beyond the cap are skipped.
	 * Heights outside minY..maxY cannot exist in the world.
	 */
	public static Parsed parse(final JsonObject message, final double minY, final double maxY) {
		if (!(message.get("list") instanceof JsonArray list)) {
			return null;
		}

		List<Entry> entries = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		int rejected = 0;
		for (JsonElement e : list) {
			Entry entry = entries.size() < MAX_ENTRIES ? entry(e, minY, maxY) : null;
			if (entry == null || !seen.add(entry.id())) {
				rejected++;
			} else {
				entries.add(entry);
			}
		}

		return new Parsed(entries, rejected);
	}

	private static Entry entry(final JsonElement e, final double minY, final double maxY) {
		if (!(e instanceof JsonObject o) || !(o.get("p") instanceof JsonArray p) || p.size() != 3
			|| !(o.get("id") instanceof JsonPrimitive id) || !id.isString() || !DECIMAL_ID.matcher(id.getAsString()).matches()
			|| !(o.get("kind") instanceof JsonPrimitive kind) || !kind.isString()) {
			return null;
		}

		Kind k = switch (kind.getAsString()) {
			case "human" -> Kind.HUMAN;
			case "vehicle" -> Kind.VEHICLE;
			default -> null;
		};
		double x = number(p.get(0)), y = number(p.get(1)), z = number(p.get(2)), yaw = number(o.get("yaw"));
		if (k == null || !(Math.abs(x) <= XZ_LIMIT) || !(Math.abs(z) <= XZ_LIMIT) || !(y >= minY && y <= maxY) || !Double.isFinite(yaw)) {
			return null;
		}

		// optional: an NPC that does not say (or says something else than true or false) is not hostile
		boolean hostile = o.get("hostile") instanceof JsonPrimitive h && h.isBoolean() && h.getAsBoolean();
		try {
			return new Entry(Long.parseUnsignedLong(id.getAsString()), x, y, z, (float) (yaw % 360.0), k, hostile);
		} catch (NumberFormatException tooBig) {
			return null; // more than 2^64 - 1
		}
	}

	/** A JSON number as a double, NaN for anything else (a string "5" is not a number here). */
	private static double number(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
	}

	/** The log line for the proxies in the world: how many of each kind, and how many came and went since the last such line. */
	public static String summary(final int humans, final int cars, final int spawned, final int removed) {
		return "proxies: " + humans + " people, " + cars + " cars (spawned " + spawned + ", removed " + removed + ")";
	}

	/**
	 * What turns the proxies that exist (id to kind) into the entries: removals first (an id that is gone, or that changed
	 * kind and is spawned again), then moves, then spawns.
	 */
	public static Plan apply(final Map<Long, Kind> current, final List<Entry> incoming) {
		List<Entry> spawn = new ArrayList<>();
		List<Entry> move = new ArrayList<>();
		List<Long> remove = new ArrayList<>();
		Set<Long> listed = new HashSet<>();
		for (Entry e : incoming) {
			listed.add(e.id());
			Kind had = current.get(e.id());
			if (had == null) {
				spawn.add(e);
			} else if (had == e.kind()) {
				move.add(e);
			} else {
				remove.add(e.id());
				spawn.add(e);
			}
		}

		for (Long id : current.keySet()) {
			if (!listed.contains(id)) {
				remove.add(id);
			}
		}

		return new Plan(spawn, move, remove);
	}
}
