// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;

/** The host's latest camera and player pose, already in Minecraft coordinates (the host converts). */
public final class HostState {
	/**
	 * @param hostFrame the host's frame number for this pose (echoed in the exported frame)
	 * @param x camera position
	 * @param yaw Minecraft yaw (0 = facing +Z), pitch (positive = down) and roll, in degrees
	 * @param fov vertical field of view, degrees
	 * @param firstPerson whether the host camera is first person (third person shows the player model)
	 * @param px the player's feet (third person)
	 * @param bodyYaw the player's body yaw (third person)
	 * @param drive Minecraft moves the player (elytra flight) and the host follows; lookYaw/lookPitch steer
	 * @param gun the player holds one of the host's guns (Steve aims it; his own item isn't drawn)
	 */
	public record Pose(
		long hostFrame, double x, double y, double z, float yaw, float pitch, float roll, float fov,
		boolean firstPerson, double px, double py, double pz, float bodyYaw, long receivedNanos,
		boolean drive, float lookYaw, float lookPitch, boolean gun
	) {
	}

	private static final long TIMEOUT_NANOS = 2_000_000_000L;
	/** Positions beyond these are garbage, not a place in the world: rejected (the server clamps moves to 3e7 horizontally). */
	private static final double XZ_LIMIT = 2.9E7;
	private static final double Y_LIMIT = 20000.0;
	private static volatile Pose latest;
	/** Cyberpunk says V is in a vehicle: Steve, his hand and the HUD are hidden. */
	private static volatile boolean vehicle;
	/** The pose this frame renders with, taken once per frame so every hook agrees. Render thread only. */
	private static Pose frame;

	private HostState() {
	}

	/**
	 * {"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"fp":bool,"pl":[x,y,z],"h":bodyYaw}. Every number is
	 * checked before the pose is built, and a bad message throws IllegalArgumentException (the link logs it and carries on, the
	 * previous pose stays): NaN or a position outside the world would reach player.setPos, whose move packet gets the player
	 * kicked by the server (that ends the singleplayer session). Pitch is clamped to +-90: the host's asin(-dy) can round past it.
	 */
	static void update(final JsonObject m) {
		JsonArray p = m.getAsJsonArray("p");
		JsonArray r = m.getAsJsonArray("r");
		JsonArray pl = m.has("pl") ? m.getAsJsonArray("pl") : p;
		double x = coordinate(p, 0, "p.x"), y = coordinate(p, 1, "p.y"), z = coordinate(p, 2, "p.z");
		float yaw = angle(r.get(0), "yaw");
		float pitch = Math.clamp(angle(r.get(1), "pitch"), -90.0F, 90.0F);
		float roll = r.size() > 2 ? angle(r.get(2), "roll") : 0.0F;
		float fov = m.has("fov") ? (float) number(m.get("fov"), "fov", 1.0, 179.0) : 70.0F;
		double px = coordinate(pl, 0, "pl.x"), py = coordinate(pl, 1, "pl.y"), pz = coordinate(pl, 2, "pl.z");
		float bodyYaw = m.has("h") ? angle(m.get("h"), "h") : yaw;
		float lookYaw = yaw;
		float lookPitch = pitch;
		if (m.has("look")) {
			JsonArray look = m.getAsJsonArray("look");
			lookYaw = angle(look.get(0), "look yaw");
			lookPitch = Math.clamp(angle(look.get(1), "look pitch"), -90.0F, 90.0F);
		}

		latest = new Pose(
			m.has("f") ? m.get("f").getAsLong() : 0L,
			x, y, z, yaw, pitch, roll, fov,
			!m.has("fp") || m.get("fp").getAsBoolean(),
			px, py, pz, bodyYaw,
			System.nanoTime(),
			m.has("drive") && m.get("drive").getAsBoolean(),
			lookYaw, lookPitch,
			m.has("gun") && m.get("gun").getAsBoolean()
		);
	}

	/**
	 * A number within min..max, inclusive. NaN and the infinities fail that test whatever way they got in: Gson's parser is
	 * lenient (NaN and Infinity literals parse as numbers) and getAsDouble also converts the strings "NaN" and "Infinity".
	 */
	private static double number(final JsonElement e, final String what, final double min, final double max) {
		double v = e.getAsDouble();
		if (!(v >= min && v <= max)) {
			throw new IllegalArgumentException(what + " out of range: " + v);
		}

		return v;
	}

	/** Component i (x, y, z) of a position: finite, and somewhere the server accepts a player. */
	private static double coordinate(final JsonArray a, final int i, final String what) {
		double limit = i == 1 ? Y_LIMIT : XZ_LIMIT;
		return number(a.get(i), what, -limit, limit);
	}

	/** A finite angle in degrees (and finite once it is a float too). */
	private static float angle(final JsonElement e, final String what) {
		return (float) number(e, what, -Float.MAX_VALUE, Float.MAX_VALUE);
	}

	public static boolean vehicle() {
		return vehicle;
	}

	static void setVehicle(final boolean inVehicle) {
		vehicle = inVehicle;
	}

	/** The latest pose if the host is still sending, else null. */
	static Pose live() {
		Pose p = latest;
		return p != null && System.nanoTime() - p.receivedNanos() < TIMEOUT_NANOS ? p : null;
	}

	/** The pose for the frame being rendered, or null when no host is attached. */
	public static Pose frame() {
		return frame;
	}

	public static void beginFrame() {
		frame = live();
		Cyberblock.active = frame != null;
	}
}
