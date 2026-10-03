package dev.cyberblock.cyber;

/** Whether a quickhack item may be used now (task C5), pure. */
public final class HackRules {
	public enum Outcome {
		OK,
		/** No cyberdeck in the inventory. */
		NO_DECK,
		/** Nothing scanned in the last 5 s. */
		NO_TARGET,
		/** The Redstone Lever on a person or a car. */
		NEED_DEVICE,
		/**
		 * Any other quickhack (people, and cars through their driver) on a device. Whether a target that is no device is a person
		 * Cyberpunk decides: it answers anything else (or anyone it protects) with a failure, and the RAM is refunded.
		 */
		NEED_NPC,
		NO_RAM
	}

	private HackRules() {
	}

	/** @param cost what the hack costs now (Ram.cost) */
	public static Outcome check(final boolean hasDeck, final boolean hasTarget, final boolean targetIsDevice, final Hack hack, final int ram,
		final int cost) {
		if (!hasDeck) {
			return Outcome.NO_DECK;
		}

		if (!hasTarget) {
			return Outcome.NO_TARGET;
		}

		if (hack.needsDevice() && !targetIsDevice) {
			return Outcome.NEED_DEVICE;
		}

		if (!hack.needsDevice() && targetIsDevice) {
			return Outcome.NEED_NPC;
		}

		return ram >= cost ? Outcome.OK : Outcome.NO_RAM;
	}
}
