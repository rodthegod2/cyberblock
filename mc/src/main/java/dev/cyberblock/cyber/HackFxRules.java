package dev.cyberblock.cyber;

/**
 * The pure rules of the quickhacks' Minecraft side (plan retest3 section 1.6): each chip's colour, upload clamp, impact
 * lengths, the cobweb cells and the creeper mask. No Minecraft state, so the tests run them directly.
 */
public final class HackFxRules {
	private HackFxRules() {
	}

	/** The chip's colour (0xRRGGBB): the action-bar line, the upload beam and the impact's dust. */
	public static int colour(final Hack hack) {
		return switch (hack) {
			case SHORT_CIRCUIT, DEVICE -> 0xFF2B2B;
			case OVERHEAT -> 0xFF8A00;
			case REBOOT_OPTICS -> 0x24243A;
			case CRIPPLE_MOVEMENT -> 0xE8E8E8;
			case CONTAGION -> 0x5BD13A;
			case SYNAPSE_BURNOUT -> 0x6FE3E0;
			case PING -> 0xFFD34A;
			case MEMORY_WIPE -> 0xB57CFF;
			case CYBERPSYCHOSIS -> 0x3FD13A;
		};
	}
}
