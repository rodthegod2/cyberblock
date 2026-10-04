package dev.cyberblock.hack.redstone;

import dev.cyberblock.device.Device;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Each owner's device table as the redstone blocks resolve their links against it (megaplan 6-R1): the same {@code devices} and
 * {@code devstate} UP messages the device layer (4M) reads, kept here by owner and Night City id. A device no list named for
 * {@link #GONE_TICKS} (10 s, as 4M's DeviceRules.GONE_TICKS) no longer counts; a session's end forgets its owner. Pure; server thread.
 */
final class DeviceTable {
	static final long GONE_TICKS = 200;

	private record Seen(Device device, long tick) {
	}

	private final Map<UUID, Map<Long, Seen>> owners = new HashMap<>();

	/** A devices list of this owner at tick now. */
	void update(final UUID owner, final List<Device> list, final long now) {
		Map<Long, Seen> table = this.owners.computeIfAbsent(owner, u -> new HashMap<>());
		for (Device d : list) {
			table.put(d.id(), new Seen(d, now));
		}
	}

	/** A devstate: the device's new state and flags (ignored for a device the table does not hold). */
	void state(final UUID owner, final long id, final int st, final int fl) {
		Map<Long, Seen> table = this.owners.get(owner);
		Seen s = table == null ? null : table.get(id);
		if (s != null) {
			table.put(id, new Seen(s.device().withState(st, fl), s.tick()));
		}
	}

	/** The owner's devices that a list named in the last 10 s. */
	List<Device> of(final UUID owner, final long now) {
		Map<Long, Seen> table = this.owners.get(owner);
		List<Device> out = new ArrayList<>();
		if (table != null) {
			for (Seen s : table.values()) {
				if (now - s.tick() < GONE_TICKS) {
					out.add(s.device());
				}
			}
		}

		return out;
	}

	@Nullable Device get(final UUID owner, final long id, final long now) {
		Map<Long, Seen> table = this.owners.get(owner);
		Seen s = table == null ? null : table.get(id);
		return s != null && now - s.tick() < GONE_TICKS ? s.device() : null;
	}

	/** Drops devices gone for 10 s. */
	void expire(final long now) {
		for (Map<Long, Seen> table : this.owners.values()) {
			table.values().removeIf(s -> now - s.tick() >= GONE_TICKS);
		}

		this.owners.values().removeIf(Map::isEmpty);
	}

	/** The owner's session ended. */
	void end(final UUID owner) {
		this.owners.remove(owner);
	}

	void clear() {
		this.owners.clear();
	}
}
