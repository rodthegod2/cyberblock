package dev.cyberblock.hack.breach;

import dev.cyberblock.device.DeviceRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Breach's rate limits on a host's devices, in server ticks (protocol.md "Phase 6"):
 * <ul>
 * <li>after a failed breach the same player may not breach that device again for {@link #COOLDOWN_TICKS}
 * ({@code cyberblock.breach.cooldown});</li>
 * <li>an owner gets at most one alarm daemon per {@link #ALARM_TICKS}: a later failure in that window is merged into the
 * alarm already raised (no daemon, no Heat), so guests cannot push a host's wanted level up by failing breach after breach.</li>
 * </ul>
 * Practice breaches (no device) are not limited. Server thread only.
 */
final class BreachLimits {
	/** One player on one device. */
	record Hold(UUID player, DeviceRegistry.Key device) {
	}

	/** The same player on the same device after a failure: 10 s. */
	static final long COOLDOWN_TICKS = 200;
	/** One alarm daemon per owner: 60 s. */
	static final long ALARM_TICKS = 1200;
	private final Map<Hold, Long> cooldowns = new HashMap<>();
	private final Map<UUID, Long> alarms = new HashMap<>();

	/** Ticks until this player may breach this device again (0: now). */
	long cooldown(final Hold hold, final long now) {
		return Math.max(0L, cooldowns.getOrDefault(hold, now) - now);
	}

	/** This player's breach of this device failed. */
	void failed(final Hold hold, final long now) {
		cooldowns.put(hold, now + COOLDOWN_TICKS);
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

	/** The devtools bypass ({@code /cyberblock test breach <x> <y> <z> ...}): the player may breach the device at once. */
	void clear(final Hold hold) {
		cooldowns.remove(hold);
	}

	/** Forgets what has run out (once a second). */
	void prune(final long now) {
		cooldowns.values().removeIf(until -> until <= now);
		alarms.values().removeIf(at -> now - at >= ALARM_TICKS);
	}

	void clear() {
		cooldowns.clear();
		alarms.clear();
	}
}
