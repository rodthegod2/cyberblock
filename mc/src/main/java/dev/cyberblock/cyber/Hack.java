package dev.cyberblock.cyber;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The quickhack items and what each sends (plan 2026-10-03 section 1.5): the item id (namespace cyberblock), the `hack`
 * wire value and its RAM cost. Synapse Burnout and Cyberpsychosis recharge in 10 s, the rest in 2 s.
 */
public enum Hack {
	SHORT_CIRCUIT("redstone_charge", 3, 2),
	OVERHEAT("blaze_chip", 4, 2),
	REBOOT_OPTICS("ink_sac_daemon", 3, 2),
	CRIPPLE_MOVEMENT("cobweb_daemon", 2, 2),
	CONTAGION("spider_eye_virus", 4, 2),
	SYNAPSE_BURNOUT("wither_skull_daemon", 6, 10),
	PING("compass_ping", 1, 2),
	MEMORY_WIPE("bottle_forgetting", 3, 2),
	CYBERPSYCHOSIS("creeper_brain", 8, 10),
	DEVICE("redstone_lever", 1, 2);

	private final String item;
	private final int ram;
	private final int cooldownSeconds;

	Hack(final String item, final int ram, final int cooldownSeconds) {
		this.item = item;
		this.ram = ram;
		this.cooldownSeconds = cooldownSeconds;
	}

	/** The item's id path, e.g. "redstone_charge". */
	public String item() {
		return this.item;
	}

	/** The `hack` value on the wire, e.g. "short_circuit". */
	public String wire() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/** RAM cost before perks. */
	public int ram() {
		return this.ram;
	}

	public int cooldownTicks() {
		return this.cooldownSeconds * 20;
	}

	/**
	 * The Redstone Lever works on devices (cameras, traffic lights, vending machines); every other hack on a person, or on a car
	 * through its driver (Cyberpunk answers "no driver" for an empty one).
	 */
	public boolean needsDevice() {
		return this == DEVICE;
	}

	public static @Nullable Hack byWire(final String wire) {
		for (Hack h : values()) {
			if (h.wire().equals(wire)) {
				return h;
			}
		}

		return null;
	}
}
