package dev.cyberblock.client.hack.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.hack.actions.ActWire;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The server's `cyberblock:ui t:"netmap"` (RemoteActions.netMap): the linked hosts (where V is, the district, a running blackout)
 * and the cameras their games stream, plus the Phase 0 (h) fallback range (0: none). Immutable; a bad entry is skipped, a bad
 * message is null. Pure.
 */
public record NetState(List<Host> hosts, List<Cam> cams, double range) {
	public static final NetState EMPTY = new NetState(List.of(), List.of(), 0.0);
	/** At most this many entries of each are read. */
	static final int MAX = 512;

	public record Host(UUID id, String name, Vec3 p, String district, boolean active, long blackout) {
	}

	public record Cam(UUID host, long id, Vec3 p, float yaw) {
	}

	public static @Nullable NetState parse(final JsonObject m) {
		try {
			List<Host> hosts = new ArrayList<>();
			for (JsonElement e : m.getAsJsonArray("hosts")) {
				Host h = host(e.getAsJsonObject());
				if (h != null && hosts.size() < MAX) {
					hosts.add(h);
				}
			}

			List<Cam> cams = new ArrayList<>();
			for (JsonElement e : m.getAsJsonArray("cams")) {
				Cam c = cam(e.getAsJsonObject());
				if (c != null && cams.size() < MAX) {
					cams.add(c);
				}
			}

			double range = m.has("range") ? m.get("range").getAsDouble() : 0.0;
			return new NetState(List.copyOf(hosts), List.copyOf(cams), Double.isFinite(range) && range > 0.0 ? range : 0.0);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Host host(final JsonObject o) {
		try {
			Vec3 p = ActWire.position(o.get("p"));
			String name = o.get("name").getAsString();
			String district = o.has("district") ? o.get("district").getAsString() : "";
			return p == null || name.length() > 64 || district.length() > 64 ? null
				: new Host(UUID.fromString(o.get("id").getAsString()), name, p, district, o.get("active").getAsBoolean(),
					Math.max(0L, o.has("blackout") ? o.get("blackout").getAsLong() : 0L));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Cam cam(final JsonObject o) {
		try {
			Vec3 p = ActWire.position(o.get("p"));
			long id = Long.parseUnsignedLong(o.get("id").getAsString());
			float yaw = o.get("yaw").getAsFloat();
			return p == null || id == 0L || !Float.isFinite(yaw) ? null : new Cam(UUID.fromString(o.get("h").getAsString()), id, p, yaw);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The host with this id, else null. */
	public @Nullable Host host(final @Nullable UUID id) {
		for (Host h : this.hosts) {
			if (h.id().equals(id)) {
				return h;
			}
		}

		return null;
	}

	/** The next host after `current` (wrapping; the first when `current` is unknown), null when there is none. */
	public @Nullable Host next(final @Nullable UUID current) {
		if (this.hosts.isEmpty()) {
			return null;
		}

		for (int i = 0; i < this.hosts.size(); i++) {
			if (this.hosts.get(i).id().equals(current)) {
				return this.hosts.get((i + 1) % this.hosts.size());
			}
		}

		return this.hosts.getFirst();
	}

	/** The active host nearest to a point (x, z), else null. */
	public @Nullable Host nearestActive(final double x, final double z) {
		Host best = null;
		double bestSq = Double.MAX_VALUE;
		for (Host h : this.hosts) {
			double d = (h.p().x - x) * (h.p().x - x) + (h.p().z - z) * (h.p().z - z);
			if (h.active() && d < bestSq) {
				bestSq = d;
				best = h;
			}
		}

		return best;
	}
}
