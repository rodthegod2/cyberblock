package dev.cyberblock.cyber;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The implants in their fixed wire order: the 17 of plan retest2 section 1.7, then the ones megaplan section 1.17 appends (the
 * firewall from Phase 6, the netrunner from Phase 8; at most 32 in all). Mantis Blades, Gorilla Arms and Monowire are ACTIVE
 * since plan retest3 section 1.8 (an ability on the Arms key L; their passive modifiers stay). Item id `cyberblock:<id>`, wire id `<id>`. Times are
 * ticks at 20 TPS; the duration is how long an ACTIVE or AUTO implant's effect lasts, the cooldown how long after that it
 * cannot fire again (0: no cooldown).
 */
public enum Cyberware {
	MANTIS_BLADES(CyberSlot.ARMS, Kind.ACTIVE, 0, 120),
	GORILLA_ARMS(CyberSlot.ARMS, Kind.ACTIVE, 0, 160),
	MONOWIRE(CyberSlot.ARMS, Kind.ACTIVE, 0, 100),
	PROJECTILE_LAUNCH_SYSTEM(CyberSlot.ARMS, Kind.ACTIVE, 0, 160),
	REINFORCED_TENDONS(CyberSlot.LEGS, Kind.PASSIVE, 0, 0),
	FORTIFIED_ANKLES(CyberSlot.LEGS, Kind.PASSIVE, 0, 0),
	LYNX_PAWS(CyberSlot.LEGS, Kind.PASSIVE, 0, 0),
	SANDEVISTAN(CyberSlot.OPERATING_SYSTEM, Kind.ACTIVE, 120, 500),
	BERSERK(CyberSlot.OPERATING_SYSTEM, Kind.ACTIVE, 160, 800),
	CYBERDECK(CyberSlot.OPERATING_SYSTEM, Kind.PASSIVE, 0, 0),
	KERENZIKOV(CyberSlot.NERVOUS_SYSTEM, Kind.AUTO, 40, 200),
	OPTICAL_CAMO(CyberSlot.INTEGUMENTARY, Kind.ACTIVE, 200, 600),
	SUBDERMAL_ARMOR(CyberSlot.INTEGUMENTARY, Kind.PASSIVE, 0, 0),
	KIROSHI_OPTICS(CyberSlot.EYES, Kind.PASSIVE, 0, 0),
	BIOMONITOR(CyberSlot.CIRCULATORY, Kind.AUTO, 0, 1800),
	SECOND_HEART(CyberSlot.CIRCULATORY, Kind.AUTO, 0, 6000),
	RAM_UPGRADE(CyberSlot.FRONTAL_CORTEX, Kind.PASSIVE, 0, 0),
	/**
	 * Megaplan section 1.17 (Phase 6): appended after the 17 of plan retest2. It raises the Breach difficulty when its owner defends
	 * (6-B1) and, from Phase 8 (8-X1), fires by itself to block one player hack, then recharges 60 s (AUTO, so the HUD shows the
	 * recharge). It shares the frontal cortex (one position) with the RAM upgrade.
	 */
	FIREWALL(CyberSlot.FRONTAL_CORTEX, Kind.AUTO, 0, 1200),
	/**
	 * Megaplan section 1.17 (Phase 8, 8-X1): appended after the firewall. Passive: player hacks on its owner last half as long. It
	 * takes the nervous system's one position from the Kerenzikov (a reflex implant against a netrunner's one).
	 */
	NETRUNNER(CyberSlot.NERVOUS_SYSTEM, Kind.PASSIVE, 0, 0);

	/** PASSIVE: always on. ACTIVE: fired with its key (Y, L or O: CyberwareRules.Trigger). AUTO: fires by itself. */
	public enum Kind {
		PASSIVE,
		ACTIVE,
		AUTO
	}

	private final CyberSlot slot;
	private final Kind kind;
	private final int durationTicks;
	private final int cooldownTicks;

	Cyberware(final CyberSlot slot, final Kind kind, final int durationTicks, final int cooldownTicks) {
		this.slot = slot;
		this.kind = kind;
		this.durationTicks = durationTicks;
		this.cooldownTicks = cooldownTicks;
	}

	/** The wire and item id, e.g. "projectile_launch_system". */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public CyberSlot slot() {
		return this.slot;
	}

	public Kind kind() {
		return this.kind;
	}

	/** Before perks (CyberwareRules.durationTicks). */
	public int durationTicks() {
		return this.durationTicks;
	}

	/** Before perks (CyberwareRules.cooldownTicks). */
	public int cooldownTicks() {
		return this.cooldownTicks;
	}

	/** The item (registered at startup). */
	public CyberwareItem item() {
		return CyberItems.CYBERWARE.get(this);
	}

	public static @Nullable Cyberware byId(final @Nullable String id) {
		for (Cyberware c : values()) {
			if (c.id().equals(id)) {
				return c;
			}
		}

		return null;
	}
}
