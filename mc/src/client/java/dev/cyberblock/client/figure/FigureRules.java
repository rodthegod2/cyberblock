package dev.cyberblock.client.figure;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * What guests see of a host's Night City people and cars (plan 2026-10-04 task 3-F1): a plain human figure and a plain car until
 * Phase 5 gives them their look. Pure: no game in here, so it can be tested.
 */
public final class FigureRules {
	/** Name tags show within this distance (m). */
	static final double TAG_RANGE = 12.0;
	/** A car's paint, its windows, its tyres, and everything of a wreck (ARGB). */
	static final int PAINT = 0xFF8C96A3, GLASS = 0xFF26313D, TYRES = 0xFF161616, WRECK = 0xFF2B2623;

	/** One box of the car, in metres from the feet centre, +z the front, and its colour. */
	record Part(double x0, double y0, double z0, double x1, double y1, double z1, int colour) {
	}

	private FigureRules() {
	}

	/** An active host's own client draws no figure: its Cyberpunk game shows the real NPCs and cars (and would show them twice). */
	public static boolean drawn(final boolean activeHost) {
		return !activeHost;
	}

	/** A living figure's name tag shows near the camera; a body or a wreck has none. */
	static boolean showTag(final boolean dead, final double distanceSq) {
		return !dead && distanceSq <= TAG_RANGE * TAG_RANGE;
	}

	/** The name tag: what it is, red when it is hostile to its host's V. */
	static Component tag(final boolean hostile, final boolean vehicle) {
		if (hostile) {
			return Component.translatableWithFallback("cyberblock.figure.hostile", "Hostile").withStyle(ChatFormatting.RED);
		}

		return vehicle ? Component.translatableWithFallback("cyberblock.figure.vehicle", "Vehicle")
			: Component.translatableWithFallback("cyberblock.figure.civilian", "Civilian");
	}

	/**
	 * The car drawn for a vehicle proxy of this hitbox (width and depth w, height h): 1.5 w long, 0.7 w wide and h high (a 3 m proxy:
	 * 4.5 m by 2.1 m, a car's size), a body, a cabin set back from the bonnet and four tyres. A wreck is all one dark colour.
	 */
	static List<Part> car(final double w, final double h, final boolean wreck) {
		double length = 1.5 * w, width = 0.7 * w, x = width / 2.0, z = length / 2.0;
		double tyre = 0.38 * h, tyreWidth = 0.14 * width, tyreLength = 0.15 * length, axle = z - 0.2 * length;
		double sill = 0.18 * h, belt = 0.6 * h;
		int paint = wreck ? WRECK : PAINT, glass = wreck ? WRECK : GLASS, tyres = wreck ? WRECK : TYRES;
		return List.of(
			new Part(-x, sill, -z, x, belt, z, paint),
			new Part(-0.92 * x, belt, -0.9 * z, 0.92 * x, h, 0.2 * z, glass),
			tyreAt(-x, axle, tyreWidth, tyreLength, tyre, tyres), tyreAt(x - tyreWidth, axle, tyreWidth, tyreLength, tyre, tyres),
			tyreAt(-x, -axle, tyreWidth, tyreLength, tyre, tyres), tyreAt(x - tyreWidth, -axle, tyreWidth, tyreLength, tyre, tyres));
	}

	private static Part tyreAt(final double x0, final double zCentre, final double width, final double length, final double h, final int colour) {
		return new Part(x0, 0.0, zCentre - length / 2.0, x0 + width, h, zCentre + length / 2.0, colour);
	}
}
