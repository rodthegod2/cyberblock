package dev.cyberblock.client.mode.van;

import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a drone pilot aims at (megaplan 8-V1, pure): of the targets the server says the Van sees, the one closest to the middle of
 * the drone's view, within {@link #CONE_COS} of it and the drone's sight. A figure's place comes from this client's own entity when
 * it tracks it (fresher than the state), else from the state.
 */
public final class VanAim {
	/** About 9 degrees off the middle of the view. */
	static final double CONE_COS = 0.988;

	private VanAim() {
	}

	/** Minecraft's look vector for a yaw and pitch in degrees. */
	public static Vec3 look(final float yaw, final float pitch) {
		double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * The target the view points at, or null.
	 *
	 * @param placeOf a target's current place (the live entity's centre, or the state's position)
	 */
	public static VanWire.@Nullable Target pick(final List<VanWire.Target> targets, final Vec3 eye, final Vec3 look, final Function<VanWire.Target, Vec3> placeOf) {
		VanWire.Target best = null;
		double bestCos = CONE_COS;
		for (VanWire.Target t : targets) {
			Vec3 to = placeOf.apply(t).subtract(eye);
			double d = to.length();
			if (d < 1e-6 || d > VanRules.DRONE_SIGHT) {
				continue;
			}

			double cos = to.dot(look) / d;
			if (cos > bestCos) {
				bestCos = cos;
				best = t;
			}
		}

		return best;
	}
}
