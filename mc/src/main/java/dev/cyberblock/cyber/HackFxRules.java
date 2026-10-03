package dev.cyberblock.cyber;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The pure rules of the quickhacks' Minecraft side (plan retest3 section 1.6): each chip's colour, pitch and impact length,
 * the upload clamp, the cobweb cells and their exclusion, the creeper mask and the beam points. No game state, so the tests
 * run them directly; HackFx applies them on the server thread.
 */
public final class HackFxRules {
	/** `hackresult.kind` values (section 1.1); anything else is dropped. */
	public static final Set<String> KINDS = Set.of("squad", "crowd", "driver", "camera", "traffic", "distract", "device");
	/** The longest upload Cyberpunk reports (seconds). */
	public static final double MAX_UPLOAD = 30.0;
	/** Spreads may arrive for this many finished hacks (ping and frenzy come after the upload completes). */
	public static final int MEMORY = 32;
	/** Real cobwebs last 6 s. */
	public static final int WEB_TICKS = 120;
	/** No cobweb is placed this close (blocks) to a player's box. */
	public static final double WEB_CLEARANCE = 1.5;
	/** Ping: hostiles within this many blocks of the target are pinged (outlined) this long. */
	public static final double PING_RADIUS = 32.0;
	public static final int PING_TICKS = 200;
	/** Beam particles are this far apart, and a beam is at most this many points (64 blocks). */
	public static final double BEAM_STEP = 0.5;
	public static final int BEAM_MAX = 128;
	/**
	 * Creeper Brain's mask (section 1.6), an original 8x8 pattern drawn in green dust above the target's head: '#' is a dust
	 * pixel, row 0 the top. Two square eyes over a stepped mouth.
	 */
	public static final List<String> CREEPER_MASK = List.of(
		"........",
		".##..##.",
		".##..##.",
		"...##...",
		"..####..",
		"..####..",
		"..#..#..",
		"........");

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

	/** How long the impact plays (ticks), section 1.6's table. */
	public static int impactTicks(final Hack hack) {
		return switch (hack) {
			case SHORT_CIRCUIT, SYNAPSE_BURNOUT, DEVICE -> 40;
			case OVERHEAT -> 100;
			case REBOOT_OPTICS, MEMORY_WIPE -> 80;
			case CRIPPLE_MOVEMENT, CONTAGION, CYBERPSYCHOSIS -> 120;
			case PING -> PING_TICKS;
		};
	}

	/** The upload sound's pitch: one step per chip, so each chip sounds its own (0.5 .. 1.85). */
	public static float pitch(final Hack hack) {
		return 0.5F + hack.ordinal() * 0.15F;
	}

	/** `hackresult.upload`: seconds, 0..30; anything not finite is 0 (instant). */
	public static double clampUpload(final double seconds) {
		return Double.isFinite(seconds) ? Math.clamp(seconds, 0.0, MAX_UPLOAD) : 0.0;
	}

	/** Upload seconds as server ticks (20 per second), after the clamp. */
	public static int uploadTicks(final double seconds) {
		return (int) Math.round(clampUpload(seconds) * 20.0);
	}

	/** Cobweb Daemon's cells: the target's feet cell, its four horizontal neighbours, and the head cell above the feet. */
	public static List<BlockPos> webCells(final BlockPos feet) {
		return List.of(feet, feet.north(), feet.south(), feet.east(), feet.west(), feet.above());
	}

	/** A web may go into this cell: no player's box is within WEB_CLEARANCE of the cell's centre. */
	public static boolean webAllowed(final BlockPos cell, final List<AABB> players) {
		Vec3 centre = Vec3.atCenterOf(cell);
		for (AABB box : players) {
			if (box.distanceToSqr(centre) < WEB_CLEARANCE * WEB_CLEARANCE) {
				return false;
			}
		}

		return true;
	}

	/** Points from `from` to `to`, BEAM_STEP apart, the first one step out, never more than BEAM_MAX (a long beam is cut). */
	public static List<Vec3> beamPoints(final Vec3 from, final Vec3 to) {
		Vec3 d = to.subtract(from);
		double length = d.length();
		List<Vec3> points = new ArrayList<>();
		if (!(length > 1.0E-6)) {
			return points;
		}

		Vec3 step = d.scale(BEAM_STEP / length);
		for (int i = 1; i * BEAM_STEP <= length && points.size() < BEAM_MAX; i++) {
			points.add(from.add(step.scale(i)));
		}

		return points;
	}

	/**
	 * The mask's dust pixels as offsets (right, up) in blocks from the mask's centre, `pixel` blocks per pixel: drawn on the
	 * plane facing the viewer, row 0 at the top.
	 */
	public static List<double[]> maskPixels(final double pixel) {
		List<double[]> out = new ArrayList<>();
		for (int row = 0; row < CREEPER_MASK.size(); row++) {
			String line = CREEPER_MASK.get(row);
			for (int col = 0; col < line.length(); col++) {
				if (line.charAt(col) == '#') {
					out.add(new double[]{(col - 3.5) * pixel, (3.5 - row) * pixel});
				}
			}
		}

		return out;
	}
}
