package dev.cyberblock.client.figure;

import dev.cyberblock.figure.VehicleModel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 12 vehicle models as boxes (megaplan task 5-F1: Java model code, no copied geometry): metres from the centre of the ground
 * footprint, +z the front, each box with a role that gives its colour (the paint is the host's {@code col}, the map's or the model's
 * own) and whether it glows (lights, thrusters, a flashing light bar). A wreck is all one dark colour and glows nowhere. Pure.
 */
public final class VehicleModels {
	public enum Role {
		PAINT, GLASS, TYRE, DARK, TRIM, CARGO, HEADLIGHT, TAILLIGHT, ACCENT, SIREN_RED, SIREN_BLUE, THRUSTER
	}

	/** One box. */
	public record Part(double x0, double y0, double z0, double x1, double y1, double z1, Role role) {
	}

	static final int GLASS = 0xFF26313D, TYRE = 0xFF161616, DARK = 0xFF2A2C30, TRIM = 0xFFE6E8EA, CARGO = 0xFFC9CCD1,
		HEADLIGHT = 0xFFFFF4C8, TAILLIGHT = 0xFFD0202A, THRUSTER = 0xFF7FD8FF, WRECK = 0xFF2B2623;
	static final int SIREN_RED_ON = 0xFFFF2020, SIREN_RED_OFF = 0xFF501414, SIREN_BLUE_ON = 0xFF2848FF, SIREN_BLUE_OFF = 0xFF141C50;
	/** Ticks each half of the light bar's flash lasts. */
	static final int FLASH_TICKS = 5;
	private static final Map<VehicleModel, List<Part>> MODELS = new EnumMap<>(VehicleModel.class);

	static {
		for (VehicleModel m : VehicleModel.values()) {
			MODELS.put(m, List.copyOf(build(m)));
		}
	}

	private VehicleModels() {
	}

	public static List<Part> parts(final VehicleModel model) {
		return MODELS.get(model);
	}

	/**
	 * A box's colour (ARGB). paint: 0xRRGGBB. chase: the light bar flashes (a hostile police car or aerodyne); ageTicks drives the
	 * flash, red and blue taking turns.
	 */
	public static int colour(final Role role, final VehicleModel model, final int paint, final boolean wreck, final boolean chase, final float ageTicks) {
		if (wreck) {
			return WRECK;
		}

		boolean redTurn = ((int) (ageTicks / FLASH_TICKS) & 1) == 0;
		return switch (role) {
			case PAINT -> 0xFF000000 | paint & 0xFFFFFF;
			case GLASS -> GLASS;
			case TYRE -> TYRE;
			case DARK -> DARK;
			case TRIM -> TRIM;
			case CARGO -> CARGO;
			case HEADLIGHT -> HEADLIGHT;
			case TAILLIGHT -> TAILLIGHT;
			case ACCENT -> switch (model) {
				case TAXI -> 0xFFFFF0A0;
				case TRAUMA_AV -> 0xFF2EC4B6;
				default -> TRIM;
			};
			case SIREN_RED -> chase && redTurn ? SIREN_RED_ON : SIREN_RED_OFF;
			case SIREN_BLUE -> chase && !redTurn ? SIREN_BLUE_ON : SIREN_BLUE_OFF;
			case THRUSTER -> THRUSTER;
		};
	}

	/** Whether the box glows (drawn full bright): lights, the taxi sign, thrusters, and a light bar while it flashes. */
	public static boolean glows(final Role role, final VehicleModel model, final boolean wreck, final boolean chase) {
		if (wreck) {
			return false;
		}

		return switch (role) {
			case HEADLIGHT, TAILLIGHT, THRUSTER -> true;
			case ACCENT -> model == VehicleModel.TAXI;
			case SIREN_RED, SIREN_BLUE -> chase;
			default -> false;
		};
	}

	private static List<Part> build(final VehicleModel m) {
		List<Part> p = new ArrayList<>();
		switch (m) {
			case SEDAN -> car(p, 4.6, 1.9, 0.30, 0.90, 1.45, -1.10, 0.60, 0.33, 1.45, 0.26);
			case SPORTS -> {
				car(p, 4.4, 2.0, 0.22, 0.72, 1.15, -0.90, 0.40, 0.34, 1.40, 0.30);
				// a rear wing on two posts
				p.add(new Part(-0.85, 0.92, -2.15, 0.85, 0.98, -1.80, Role.DARK));
				mirror(p, 0.68, 0.72, -2.05, 0.60, 0.92, -1.90, Role.DARK);
			}
			case TAXI -> {
				car(p, 4.6, 1.9, 0.30, 0.90, 1.45, -1.10, 0.60, 0.33, 1.45, 0.26);
				p.add(new Part(-0.30, 1.45, -0.35, 0.30, 1.62, 0.05, Role.ACCENT));
			}
			case POLICE -> {
				car(p, 4.8, 1.95, 0.30, 0.92, 1.48, -1.10, 0.65, 0.34, 1.50, 0.27);
				// white doors, a push bar and the light bar (red on the left, blue on the right)
				p.add(new Part(-0.985, 0.50, -0.95, 0.985, 0.80, 0.95, Role.TRIM));
				p.add(new Part(-0.60, 0.30, 2.40, 0.60, 0.80, 2.55, Role.DARK));
				p.add(new Part(-0.62, 1.48, -0.30, -0.04, 1.62, 0.02, Role.SIREN_RED));
				p.add(new Part(0.04, 1.48, -0.30, 0.62, 1.62, 0.02, Role.SIREN_BLUE));
			}
			case VAN -> {
				box(p, 5.0, 2.0, 0.35, 2.10, 0.36, 1.60, 0.28);
				p.add(new Part(-0.90, 1.15, 2.45, 0.90, 1.90, 2.52, Role.GLASS));
				p.add(new Part(-1.01, 1.20, 1.10, 1.01, 1.85, 2.20, Role.GLASS));
			}
			case ARMORED_VAN -> {
				box(p, 5.6, 2.3, 0.45, 2.40, 0.45, 1.80, 0.36);
				// armour skirts, a slit windscreen and a ram
				mirror(p, 1.21, 0.45, -2.50, 1.15, 1.30, 2.50, Role.DARK);
				p.add(new Part(-0.85, 1.75, 2.80, 0.85, 1.95, 2.86, Role.GLASS));
				p.add(new Part(-0.95, 0.40, 2.80, 0.95, 1.10, 3.00, Role.DARK));
			}
			case DELIVERY -> {
				// a cab in front of a tall box
				wheels(p, 2.2, 0.40, 2.05, -1.90, 0.30);
				p.add(new Part(-1.10, 0.40, 1.20, 1.10, 2.20, 3.10, Role.PAINT));
				p.add(new Part(-1.00, 1.30, 3.10, 1.00, 2.05, 3.16, Role.GLASS));
				p.add(new Part(-1.12, 0.45, -3.10, 1.12, 2.90, 1.15, Role.CARGO));
				p.add(new Part(-1.13, 0.45, -1.00, 1.13, 0.75, 1.15, Role.PAINT));
				lights(p, 1.10, 0.95, 3.10, -3.10);
			}
			case TRUCK -> {
				// a semi: cab and trailer, three axles
				wheels(p, 2.5, 0.50, 2.60, -2.80, 0.36);
				mirror(p, 1.25, 0.0, -1.70, 0.89, 1.0, -0.70, Role.TYRE);
				p.add(new Part(-1.25, 0.55, 1.60, 1.25, 3.00, 3.75, Role.PAINT));
				p.add(new Part(-1.15, 1.70, 3.75, 1.15, 2.70, 3.82, Role.GLASS));
				p.add(new Part(-1.27, 0.95, -3.75, 1.27, 3.40, 1.40, Role.CARGO));
				p.add(new Part(-0.20, 3.00, 2.70, 0.20, 3.40, 3.10, Role.DARK));
				lights(p, 1.25, 1.10, 3.75, -3.75);
			}
			case PICKUP -> {
				wheels(p, 2.0, 0.42, 1.75, -1.75, 0.30);
				double x = 1.0, z = 2.7;
				p.add(new Part(-x, 0.40, -0.30, x, 1.00, z, Role.PAINT));
				p.add(new Part(-0.90, 1.00, -0.30, 0.90, 1.75, 1.00, Role.GLASS));
				p.add(new Part(-0.88, 1.75, -0.20, 0.88, 1.82, 0.90, Role.PAINT));
				// the open bed: floor, sides and tailgate
				p.add(new Part(-x, 0.40, -z, x, 0.55, -0.30, Role.PAINT));
				mirror(p, x, 0.55, -z, x - 0.08, 1.00, -0.30, Role.PAINT);
				p.add(new Part(-x, 0.55, -z, x, 1.00, -z + 0.08, Role.PAINT));
				p.add(new Part(-0.95, 0.35, z, 0.95, 0.60, z + 0.10, Role.DARK));
				lights(p, x, 0.75, z, -z);
			}
			case BIKE -> {
				p.add(new Part(-0.08, 0.0, 0.42, 0.08, 0.66, 1.08, Role.TYRE));
				p.add(new Part(-0.09, 0.0, -1.08, 0.09, 0.66, -0.42, Role.TYRE));
				p.add(new Part(-0.18, 0.45, -0.30, 0.18, 0.85, 0.45, Role.PAINT));
				p.add(new Part(-0.16, 0.80, -0.75, 0.16, 0.90, -0.15, Role.DARK));
				p.add(new Part(-0.05, 0.33, 0.60, 0.05, 1.00, 0.70, Role.DARK));
				p.add(new Part(-0.36, 1.00, 0.55, 0.36, 1.05, 0.62, Role.DARK));
				p.add(new Part(-0.08, 0.85, 0.70, 0.08, 0.97, 0.78, Role.HEADLIGHT));
				p.add(new Part(-0.06, 0.78, -0.82, 0.06, 0.86, -0.75, Role.TAILLIGHT));
				p.add(new Part(0.14, 0.25, -0.90, 0.24, 0.35, -0.20, Role.DARK));
			}
			case TRAUMA_AV, NCPD_AV -> {
				// an aerodyne: a hovering hull, a cockpit, a stripe, a fin and four thruster pods glowing underneath
				p.add(new Part(-1.20, 0.90, -3.00, 1.20, 2.40, 3.00, Role.PAINT));
				p.add(new Part(-1.00, 1.50, 3.00, 1.00, 2.25, 3.25, Role.GLASS));
				p.add(new Part(-1.21, 1.30, -3.00, 1.21, 1.50, 3.00, Role.ACCENT));
				p.add(new Part(-0.10, 2.40, -3.00, 0.10, 3.00, -2.00, Role.PAINT));
				for (double z : new double[] {1.6, -2.6}) {
					mirror(p, 1.90, 0.70, z, 1.20, 1.50, z + 1.0, Role.DARK);
					mirror(p, 1.80, 0.55, z + 0.1, 1.30, 0.70, z + 0.9, Role.THRUSTER);
				}

				if (m == VehicleModel.NCPD_AV) {
					p.add(new Part(-0.62, 2.40, -0.30, -0.04, 2.54, 0.02, Role.SIREN_RED));
					p.add(new Part(0.04, 2.40, -0.30, 0.62, 2.54, 0.02, Role.SIREN_BLUE));
				}

				p.add(new Part(-0.9, 1.0, 3.0, -0.6, 1.2, 3.05, Role.HEADLIGHT));
				p.add(new Part(0.6, 1.0, 3.0, 0.9, 1.2, 3.05, Role.HEADLIGHT));
			}
		}

		return p;
	}

	/**
	 * A car: length l, width w, sill and belt line heights, roof height, the cabin from cab0 to cab1 (z), wheels of radius r on axles
	 * at +-axle, wheel width ww; with bumpers, headlights and tail lights.
	 */
	private static void car(final List<Part> p, final double l, final double w, final double sill, final double belt, final double roof,
		final double cab0, final double cab1, final double r, final double axle, final double ww) {
		double x = w / 2.0, z = l / 2.0;
		wheels(p, w, r, axle, -axle, ww);
		p.add(new Part(-x, sill, -z, x, belt, z, Role.PAINT));
		p.add(new Part(-0.9 * x, belt, cab0, 0.9 * x, roof - 0.06, cab1, Role.GLASS));
		p.add(new Part(-0.88 * x, roof - 0.06, cab0 + 0.1, 0.88 * x, roof, cab1 - 0.1, Role.PAINT));
		p.add(new Part(-0.95 * x, sill - 0.05, z, 0.95 * x, sill + 0.2, z + 0.08, Role.DARK));
		p.add(new Part(-0.95 * x, sill - 0.05, -z - 0.08, 0.95 * x, sill + 0.2, -z, Role.DARK));
		lights(p, x, belt - 0.2, z, -z);
	}

	/** A box-bodied vehicle (vans): body from sill to top, wheels, lights. */
	private static void box(final List<Part> p, final double l, final double w, final double sill, final double top, final double r,
		final double axle, final double ww) {
		double x = w / 2.0, z = l / 2.0;
		wheels(p, w, r, axle, -axle, ww);
		p.add(new Part(-x, sill, -z, x, top, z, Role.PAINT));
		p.add(new Part(-0.95 * x, sill - 0.05, z, 0.95 * x, sill + 0.25, z + 0.08, Role.DARK));
		lights(p, x, sill + 0.45, z, -z);
	}

	/** Four wheels of radius r at axles zFront and zRear on a body w wide, ww wide each, sticking out 2 cm. */
	private static void wheels(final List<Part> p, final double w, final double r, final double zFront, final double zRear, final double ww) {
		double x = w / 2.0 + 0.02;
		for (double az : new double[] {zFront, zRear}) {
			mirror(p, x, 0.0, az - r, x - ww, 2.0 * r, az + r, Role.TYRE);
		}
	}

	/** Headlights at the front (z front) and tail lights at the back (z rear), centred on height y, on a body half-width x. */
	private static void lights(final List<Part> p, final double x, final double y, final double front, final double rear) {
		mirror(p, 0.85 * x, y, front, 0.45 * x, y + 0.12, front + 0.03, Role.HEADLIGHT);
		mirror(p, 0.90 * x, y, rear - 0.03, 0.50 * x, y + 0.12, rear, Role.TAILLIGHT);
	}

	/** A box on the left (x from -outer to -inner) and its mirror on the right. */
	private static void mirror(final List<Part> p, final double outer, final double y0, final double z0, final double inner, final double y1,
		final double z1, final Role role) {
		p.add(new Part(-outer, y0, z0, -inner, y1, z1, role));
		p.add(new Part(inner, y0, z0, outer, y1, z1, role));
	}
}
