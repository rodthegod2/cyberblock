package dev.cyberblock.mode.overlord;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The host-set Overlord intensity of one match (megaplan 8-O1 fairness): how fast Threat Points come back, how many can be banked,
 * how many Overlord spawns may live at once and how close to a player one may land. Chill is the gentle end, Brutal the hard one.
 */
public enum Intensity {
	CHILL(1.0, 60, 6, 16.0),
	NORMAL(2.0, 120, 12, 12.0),
	BRUTAL(4.0, 240, 24, 8.0);

	/** Threat Points per second. */
	private final double regen;
	/** The most Threat Points a match can bank. */
	private final int maxPoints;
	/** The most Overlord spawns alive at once in one match. */
	private final int cap;
	/** No spawn lands closer than this to any player (blocks, horizontal). */
	private final double minDistance;

	Intensity(final double regen, final int maxPoints, final int cap, final double minDistance) {
		this.regen = regen;
		this.maxPoints = maxPoints;
		this.cap = cap;
		this.minDistance = minDistance;
	}

	public double regen() {
		return this.regen;
	}

	public int maxPoints() {
		return this.maxPoints;
	}

	public int cap() {
		return this.cap;
	}

	public double minDistance() {
		return this.minDistance;
	}

	/** chill, normal, brutal (the command and wire name). */
	public String key() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable Intensity byKey(final String key) {
		for (Intensity i : values()) {
			if (i.key().equals(key)) {
				return i;
			}
		}

		return null;
	}
}
