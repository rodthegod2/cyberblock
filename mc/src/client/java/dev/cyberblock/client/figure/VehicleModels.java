package dev.cyberblock.client.figure;

import dev.cyberblock.figure.VehicleModel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 12 vehicle models as boxes (megaplan task 5-F1: Java model code, no copied geometry), exactly the UV layout 5A's
 * art/gen_vehicle_art.py states as its contract with this renderer: per part the texture offset (u, v) in a 128 x 128 texture, the
 * box size (w, h, d) and the corner of each box that shares that UV region, in model pixels with the vanilla axes (y down, y = 0 the
 * ground, the nose towards -z, -x the vehicle's right), half scale (drawn at 2x: one model pixel is 1/8 block). Each model draws
 * three layers over the same boxes: the greyscale paint tinted by the vehicle's colour, the untinted detail (glass, tyres, lights,
 * trims), and for the emergency vehicles the two halves of the light bar, glowing in turn while the vehicle is hostile (a chase).
 * Pure: no game in here.
 */
public final class VehicleModels {
	/** One UV region and the boxes that use it. */
	public record Part(String name, int u, int v, int w, int h, int d, List<int[]> at) {
	}

	/** Texture size (both ways). */
	public static final int TEXTURE = 128;
	/** One model pixel is this many vanilla model pixels (the geometry is half scale). */
	public static final float SCALE = 2.0F;
	/** Ticks each half of the light bar glows. */
	static final int FLASH_TICKS = 5;
	/** A wreck's paint and detail are multiplied by this (ARGB). */
	static final int WRECK = 0xFF2B2623;
	private static final Map<VehicleModel, List<Part>> MODELS = new EnumMap<>(VehicleModel.class);

	private static final List<int[]> WHEELS_SEDAN = List.of(new int[] {-9, -6, -14}, new int[] {6, -6, -14}, new int[] {-9, -6, 8}, new int[] {6, -6, 8});
	private static final List<int[]> WHEELS_SPORTS = List.of(new int[] {-9, -6, -13}, new int[] {6, -6, -13}, new int[] {-9, -6, 8}, new int[] {6, -6, 8});

	static {
		List<Part> sedan = List.of(part("body", 0, 0, 16, 6, 36, -8, -8, -18), part("cabin", 0, 42, 14, 5, 16, -7, -13, -6),
			new Part("wheel", 60, 42, 3, 6, 6, WHEELS_SEDAN));
		List<Part> aerodyne = List.of(part("fuselage", 0, 0, 16, 8, 34, -8, -14, -17), part("cockpit", 0, 42, 12, 4, 10, -6, -18, -15),
			new Part("pod", 44, 42, 6, 4, 8, List.of(new int[] {-14, -10, -14}, new int[] {8, -10, -14}, new int[] {-14, -10, 6}, new int[] {8, -10, 6})),
			part("fin", 72, 42, 2, 4, 8, -1, -18, 9), part("light_bar", 92, 42, 8, 1, 2, -4, -15, 0));
		MODELS.put(VehicleModel.SEDAN, sedan);
		MODELS.put(VehicleModel.SPORTS_CAR, List.of(part("body", 0, 0, 16, 5, 34, -8, -7, -17), part("cabin", 0, 39, 14, 4, 13, -7, -11, -3),
			part("spoiler", 54, 39, 14, 1, 3, -7, -8, 13), new Part("wheel", 88, 39, 3, 6, 6, WHEELS_SPORTS)));
		MODELS.put(VehicleModel.TRUCK, List.of(part("chassis", 0, 0, 18, 3, 44, -9, -7, -22), part("cab", 0, 47, 18, 12, 12, -9, -19, -22),
			part("cargo", 0, 71, 18, 16, 30, -9, -23, -8), new Part("wheel", 60, 47, 4, 7, 7, List.of(new int[] {-10, -7, -20}, new int[] {6, -7, -20},
				new int[] {-10, -7, 7}, new int[] {6, -7, 7}, new int[] {-10, -7, 14}, new int[] {6, -7, 14}))));
		MODELS.put(VehicleModel.VAN, List.of(part("body", 0, 0, 16, 13, 34, -8, -16, -17), new Part("wheel", 0, 47, 3, 6, 6, WHEELS_SPORTS)));
		MODELS.put(VehicleModel.BIKE, List.of(part("frame", 0, 0, 4, 5, 18, -2, -10, -9), part("seat", 44, 0, 4, 1, 6, -2, -11, 0),
			part("handlebar", 64, 0, 10, 1, 1, -5, -12, -8), new Part("wheel", 0, 23, 2, 8, 8, List.of(new int[] {-1, -8, -13}, new int[] {-1, -8, 5}))));
		MODELS.put(VehicleModel.TAXI, plus(sedan, part("sign", 78, 42, 6, 2, 3, -3, -15, 0)));
		MODELS.put(VehicleModel.POLICE_CRUISER, plus(sedan, part("light_bar", 78, 47, 12, 1, 3, -6, -14, 0)));
		MODELS.put(VehicleModel.ARMOURED_VAN, List.of(part("body", 0, 0, 18, 14, 36, -9, -17, -18), part("ram", 0, 50, 18, 3, 2, -9, -6, -20),
			new Part("wheel", 40, 50, 4, 7, 7, List.of(new int[] {-10, -7, -14}, new int[] {6, -7, -14}, new int[] {-10, -7, 7}, new int[] {6, -7, 7})),
			part("hatch", 62, 50, 8, 2, 8, -4, -19, -2)));
		MODELS.put(VehicleModel.TRAUMA_AERODYNE, aerodyne);
		MODELS.put(VehicleModel.NCPD_AERODYNE, aerodyne);
		MODELS.put(VehicleModel.DELIVERY_TRUCK, List.of(part("cargo", 0, 0, 16, 15, 24, -8, -18, -10), part("cab", 0, 39, 16, 11, 10, -8, -14, -20),
			new Part("wheel", 52, 39, 3, 6, 6, List.of(new int[] {-9, -6, -17}, new int[] {6, -6, -17}, new int[] {-9, -6, 7}, new int[] {6, -6, 7}))));
		MODELS.put(VehicleModel.PICKUP, List.of(part("body", 0, 0, 16, 6, 36, -8, -8, -18), part("cabin", 0, 42, 14, 6, 12, -7, -14, -8),
			new Part("bed_side", 52, 42, 1, 3, 14, List.of(new int[] {-8, -11, 4}, new int[] {7, -11, 4})), part("tailgate", 82, 42, 14, 3, 1, -7, -11, 17),
			new Part("wheel", 0, 60, 3, 6, 6, WHEELS_SEDAN)));
	}

	private VehicleModels() {
	}

	public static List<Part> parts(final VehicleModel model) {
		return MODELS.get(model);
	}

	/** The paint's tint (ARGB): the vehicle's colour, or the wreck's dark. */
	public static int paint(final int colour, final boolean wreck) {
		return wreck ? WRECK : 0xFF000000 | colour & 0xFFFFFF;
	}

	/** The detail layer's tint: untinted, or darkened for a wreck. */
	public static int detail(final boolean wreck) {
		return wreck ? WRECK : 0xFFFFFFFF;
	}

	/**
	 * Which half of the light bar glows (0 for lights_a, 1 for lights_b), or -1 for none: only a living emergency vehicle in a chase
	 * flashes, the halves taking turns every FLASH_TICKS.
	 */
	public static int flashing(final VehicleModel model, final boolean chase, final boolean wreck, final float ageTicks) {
		return model.siren() && chase && !wreck ? (int) (ageTicks / FLASH_TICKS) & 1 : -1;
	}

	private static Part part(final String name, final int u, final int v, final int w, final int h, final int d, final int x, final int y, final int z) {
		return new Part(name, u, v, w, h, d, List.of(new int[] {x, y, z}));
	}

	private static List<Part> plus(final List<Part> base, final Part extra) {
		return java.util.stream.Stream.concat(base.stream(), java.util.stream.Stream.of(extra)).toList();
	}
}
