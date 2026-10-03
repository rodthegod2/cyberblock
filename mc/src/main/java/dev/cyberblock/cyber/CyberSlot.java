package dev.cyberblock.cyber;

import java.util.Locale;

/**
 * The ripperdoc's body slots (plan retest2 section 1.7): Cyberpunk's categories, each holding `capacity` implants. A slot
 * position is saved as the key "<slot>/<n>" (CyberData.CYBERWARE). The cyberware screen draws the first four in its left
 * column and the rest in the right one. The Operating System slot has two fixed positions (plan retest3 section 1.8): the
 * cyberdeck in 0, the Sandevistan or Berserk in 1, so both can run at once.
 */
public enum CyberSlot {
	ARMS(1),
	LEGS(1),
	OPERATING_SYSTEM(2),
	NERVOUS_SYSTEM(1),
	INTEGUMENTARY(2),
	EYES(1),
	CIRCULATORY(2),
	FRONTAL_CORTEX(1);

	private final int capacity;

	CyberSlot(final int capacity) {
		this.capacity = capacity;
	}

	/** "operating_system". */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public int capacity() {
		return this.capacity;
	}

	public String langKey() {
		return "cyberblock.cyberware.slot." + this.id();
	}

	/** The fixed position of an implant in its slot: 0 for the cyberdeck, 1 for Sandevistan and Berserk, -1 (first free) for the rest. */
	public static int position(final Cyberware cyberware) {
		return switch (cyberware) {
			case CYBERDECK -> 0;
			case SANDEVISTAN, BERSERK -> 1;
			default -> -1;
		};
	}

	/** The saved key of position n (0 .. capacity - 1), e.g. "integumentary/1". */
	public String key(final int n) {
		return this.id() + "/" + n;
	}
}
