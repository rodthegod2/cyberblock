package dev.cyberblock.cyber;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The 20 perks (plan 2026-10-03 section 1.5) in branch order, which is also purchase order: five branches of four tiers,
 * tier n needing tier n-1 of its branch. Their `perks` wire id is the lower-case name.
 */
public enum Perk {
	IRON_SKIN, GORILLA_ARMS, PAINKILLER, JUGGERNAUT,
	DOUBLE_JUMP, SANDEVISTAN, QUICK_DRAW, DODGE,
	TINKERER, SMART_ARROWS, FIELD_MEDIC, DEMOLITIONS,
	EXTRA_RAM, CHEAP_HACKS, CHAIN_HACK, OVERCLOCK,
	OPTICAL_CAMO, HEADHUNTER, COLD_BLOOD, SILENT_STEPS;

	public static final int TIERS = 4;
	/** Body, Reflexes, Technical, Intelligence, Cool (the art's skill_*.png names). */
	public static final String[] BRANCHES = {"body", "reflexes", "technical", "intelligence", "cool"};
	private static final int[] COST = {3, 5, 8, 12};

	/** The wire id, e.g. "iron_skin". */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/** 0..4, an index into BRANCHES. */
	public int branch() {
		return this.ordinal() / TIERS;
	}

	/** 1..4. */
	public int tier() {
		return this.ordinal() % TIERS + 1;
	}

	/** XP levels it costs. */
	public int cost() {
		return COST[this.tier() - 1];
	}

	/** The perk one tier below in the same branch, or null for a first tier. */
	public @Nullable Perk prerequisite() {
		return this.tier() == 1 ? null : values()[this.ordinal() - 1];
	}

	public static @Nullable Perk byId(final String id) {
		for (Perk p : values()) {
			if (p.id().equals(id)) {
				return p;
			}
		}

		return null;
	}
}
