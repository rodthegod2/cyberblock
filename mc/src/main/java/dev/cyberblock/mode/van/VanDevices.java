package dev.cyberblock.mode.van;

import dev.cyberblock.device.Device;
import dev.cyberblock.device.DeviceRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The devices each host's game streams (megaplan 4-M1's `devices` and `devstate` UP), as the Van needs them: where they are and
 * what they are, so the screen and the drones can list them and the Hack Wheel can send a `devact` for one. A device that no list
 * named for 10 s is gone ({@link DeviceRules#gone}), as on the device layer. Pure (the clock is passed in).
 */
final class VanDevices {
	private record Seen(Device device, long tick) {
	}

	private final Map<UUID, Map<Long, Seen>> byOwner = new HashMap<>();

	/** A `devices` list: every entry is the device's current data. */
	void update(final UUID owner, final List<Device> list, final long now) {
		Map<Long, Seen> m = this.byOwner.computeIfAbsent(owner, k -> new HashMap<>());
		for (Device d : list) {
			m.put(d.id(), new Seen(d, now));
		}
	}

	/** A `devstate`: the device's new state (unknown devices are ignored: the next list brings them). */
	void state(final UUID owner, final long id, final int st, final int fl) {
		Map<Long, Seen> m = this.byOwner.get(owner);
		Seen s = m == null ? null : m.get(id);
		if (s != null) {
			m.put(id, new Seen(s.device().withState(st, fl), s.tick()));
		}
	}

	/** The owner's live devices. */
	List<Device> of(final UUID owner, final long now) {
		Map<Long, Seen> m = this.byOwner.get(owner);
		List<Device> out = new ArrayList<>();
		if (m != null) {
			m.values().removeIf(s -> DeviceRules.gone(s.tick(), now));
			m.values().forEach(s -> out.add(s.device()));
		}

		return out;
	}

	/** Every owner with devices now. */
	List<UUID> owners() {
		return List.copyOf(this.byOwner.keySet());
	}

	/** One device of an owner, or null when it is unknown or gone. */
	@Nullable Device get(final UUID owner, final long id, final long now) {
		Map<Long, Seen> m = this.byOwner.get(owner);
		Seen s = m == null ? null : m.get(id);
		return s == null || DeviceRules.gone(s.tick(), now) ? null : s.device();
	}

	/** The owner's session ended. */
	void end(final UUID owner) {
		this.byOwner.remove(owner);
	}

	void clear() {
		this.byOwner.clear();
	}
}
