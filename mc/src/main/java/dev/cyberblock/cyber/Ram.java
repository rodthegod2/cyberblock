package dev.cyberblock.cyber;

/** RAM rules (task C2, plan retest2 section 1.7), pure: the server applies them to the player's RAM attachment. */
public final class Ram {
	public static final int BASE_MAX = 8;
	/** extra_ram (perk) and ram_upgrade (implant) each add this much maximum. */
	public static final int EXTRA = 4;
	/** A RAM shard restores this much. */
	public static final int SHARD = 4;
	/** One RAM point every 3 s (60 ticks); overclock halves it. */
	public static final int REGEN_TICKS = 60;

	private Ram() {
	}

	/** Maximum RAM: none at all without a cyberdeck; extra_ram and ram_upgrade add up. */
	public static int max(final boolean hasDeck, final boolean extraRam, final boolean ramUpgrade) {
		return hasDeck ? BASE_MAX + (extraRam ? EXTRA : 0) + (ramUpgrade ? EXTRA : 0) : 0;
	}

	/** A cyberdeck counts when it is installed, or carried while the Operating System slot is empty. */
	public static boolean hasDeck(final boolean installed, final boolean osSlotEmpty, final boolean carried) {
		return installed || osSlotEmpty && carried;
	}

	public static int regenTicks(final boolean overclock) {
		return overclock ? REGEN_TICKS / 2 : REGEN_TICKS;
	}

	/**
	 * RAM after one server tick: capped at the maximum (losing the cyberdeck empties it), otherwise +1 on every
	 * regenTicks-th tick of the counter.
	 */
	public static int tick(final int ram, final int max, final long tick, final boolean overclock) {
		if (ram >= max) {
			return max;
		}

		int now = Math.max(ram, 0);
		return tick % regenTicks(overclock) == 0 ? now + 1 : now;
	}

	/** RAM after using a shard. */
	public static int shard(final int ram, final int max) {
		return Math.min(max, Math.max(ram, 0) + SHARD);
	}

	/** What a quickhack costs: cheap_hacks takes one off, never below 1. */
	public static int cost(final Hack hack, final boolean cheapHacks) {
		return cheapHacks ? Math.max(1, hack.ram() - 1) : hack.ram();
	}
}
