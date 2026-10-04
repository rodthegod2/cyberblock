package dev.cyberblock.hack.breach;

import dev.cyberblock.device.DeviceRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Breach's rate limits on a host's devices, in server ticks (megaplan 6-B1 and the hacking rate limits):
 * <ul>
 * <li>a device that was breached (solved or failed) is closed to breaches for {@link #DEVICE_TICKS};</li>
 * <li>a player whose device breach failed may not breach any device for {@link #PLAYER_TICKS};</li>
 * <li>an owner gets at most one alarm daemon per {@link #ALARM_TICKS}: a later failure in that window is merged into the
 * alarm already raised (no daemon, no Heat), so a guest cannot push a host's wanted level up by failing breach after breach.</li>
 * </ul>
 * Practice breaches (no device) are not limited. Server thread only.
 */
final class BreachLimits {
	/** A device after a breach that ended with a pick: 30 s. */
	static final long DEVICE_TICKS = 600;
	/** A player after a failed device breach, across every device: 30 s. */
	static final long PLAYER_TICKS = 600;
	/** One alarm daemon per owner: 60 s. */
	static final long ALARM_TICKS = 1200;
	private final Map<DeviceRegistry.Key, Long> devices = new HashMap<>();
	private final Map<UUID, Long> players = new HashMap<>();
	private final Map<UUID, Long> alarms = new HashMap<>();

	/** Ticks until the device may be breached again (0: now). */
	long deviceWait(final DeviceRegistry.Key device, final long now) {
		return Math.max(0L, devices.getOrDefault(device, now) - now);
	}

	/** Ticks until the player may breach a device again (0: now). */
	long playerWait(final UUID player, final long now) {
		return Math.max(0L, players.getOrDefault(player, now) - now);
	}

	/** A device breach ended with at least one pick: the device closes, and after a failure the player too. */
	void ended(final DeviceRegistry.Key device, final UUID player, final boolean failed, final long now) {
		devices.put(device, now + DEVICE_TICKS);
		if (failed) {
			players.put(player, now + PLAYER_TICKS);
		}
	}

	/** May the owner get an alarm daemon now? True records it; false means one was sent within {@link #ALARM_TICKS}. */
	boolean alarm(final UUID owner, final long now) {
		Long last = alarms.get(owner);
		if (last != null && now - last < ALARM_TICKS) {
			return false;
		}

		alarms.put(owner, now);
		return true;
	}

	/** The devtools bypass ({@code /cyberblock test breach <x> <y> <z> ...}): the device and the player may breach at once. */
	void clear(final DeviceRegistry.Key device, final UUID player) {
		devices.remove(device);
		players.remove(player);
	}

	/** Forgets what has run out (once a second). */
	void prune(final long now) {
		devices.values().removeIf(until -> until <= now);
		players.values().removeIf(until -> until <= now);
		alarms.values().removeIf(at -> now - at >= ALARM_TICKS);
	}

	void clear() {
		devices.clear();
		players.clear();
		alarms.clear();
	}
}
