package dev.cyberblock.hack.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The Night City cameras the Net Map offers for recon (megaplan task 6-I1): every `camera` entry of each host's `devices` lists
 * (Phase 4, section 1.9), with its Minecraft position and facing, kept while a list names it (a device in no list for 10 s is gone,
 * as the device layer's rule). A bad entry is skipped. Server thread; pure (the clock is passed in).
 */
public final class NetIndex {
	static final long TTL_MS = 10_000L;
	/** At most this many cameras per host (a list has at most 64 devices; older ones expire). */
	static final int MAX_PER_OWNER = 256;

	/** One camera of `owner`'s game. */
	public record Cam(UUID owner, long id, double x, double y, double z, float yaw, long seenMs) {
	}

	private final Map<UUID, Map<Long, Cam>> cams = new HashMap<>();

	/** A `devices` list of this owner: its cameras are (re)noted. */
	public void devices(final UUID owner, final JsonObject m, final long now) {
		JsonElement list = m.get("list");
		if (list == null || !list.isJsonArray()) {
			return;
		}

		Map<Long, Cam> mine = this.cams.computeIfAbsent(owner, k -> new HashMap<>());
		for (JsonElement e : list.getAsJsonArray()) {
			Cam cam = camera(owner, e, now);
			if (cam != null && (mine.size() < MAX_PER_OWNER || mine.containsKey(cam.id()))) {
				mine.put(cam.id(), cam);
			}
		}
	}

	private static @Nullable Cam camera(final UUID owner, final JsonElement e, final long now) {
		try {
			JsonObject d = e.getAsJsonObject();
			if (!"camera".equals(d.get("kind").getAsString())) {
				return null;
			}

			long id = Long.parseUnsignedLong(d.get("id").getAsString());
			JsonArray p = d.getAsJsonArray("p");
			double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
			float yaw = d.has("yaw") ? d.get("yaw").getAsFloat() : 0.0F;
			boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw);
			return id != 0L && finite && p.size() == 3 ? new Cam(owner, id, x, y, z, yaw, now) : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** The camera, when a list named it in the last 10 s. */
	public @Nullable Cam camera(final UUID owner, final long id, final long now) {
		Map<Long, Cam> mine = this.cams.get(owner);
		Cam cam = mine == null ? null : mine.get(id);
		return cam != null && now - cam.seenMs() <= TTL_MS ? cam : null;
	}

	/** Every live camera (cameras gone for 10 s are forgotten now). */
	public List<Cam> cameras(final long now) {
		List<Cam> out = new ArrayList<>();
		for (Map<Long, Cam> mine : this.cams.values()) {
			mine.values().removeIf(c -> now - c.seenMs() > TTL_MS);
			out.addAll(mine.values());
		}

		return out;
	}

	/** The owner's session ended: its cameras go. */
	public void end(final UUID owner) {
		this.cams.remove(owner);
	}

	public void clear() {
		this.cams.clear();
	}
}
