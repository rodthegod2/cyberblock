// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Bridge;
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
	 * @param drive the reference's host-driven flight flag (cam "drive", "look"); unused: Flight decides when Minecraft moves Steve
	 * @param gun the player holds one of the host's guns (Steve aims it; his own item isn't drawn)
	 * @param fly the host follows Steve's glide with V (cam "fly")
	 * @param menu a Cyberpunk menu is open: the game is paused (cam "menu")
	 * @param air V is off the ground: jumping or falling (cam "air")
	 * @param held the game holds V (a scene, workspot, braindance or moving elevator): it cannot be moved (cam "held")
	 */
	public record Pose(
		long hostFrame, double x, double y, double z, float yaw, float pitch, float roll, float fov,
		boolean firstPerson, double px, double py, double pz, float bodyYaw, long receivedNanos,
		boolean drive, float lookYaw, float lookPitch, boolean gun,
		boolean fly, boolean menu, boolean air, boolean held
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
	 * {"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"fp":bool,"pl":[x,y,z],"h":bodyYaw}, plus the flags
	 * "cr", "fly", "menu", "air" and "held" (false when absent or not a boolean: an older host never sends them). Every number is
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
			m.has("gun") && m.get("gun").getAsBoolean(),
			flag(m, "fly"), flag(m, "menu"), flag(m, "air"), flag(m, "held")
		);
		// V crouched (layers 3-6): Steve crouches too (KeyboardInputMixin), and the cyber track reads it for optical camo
		Bridge.hostCrouching = flag(m, "cr");
		// V cannot be moved: the server refuses ender pearl teleports (ThrownEnderpearlMixin)
		Bridge.hostHeld = flag(m, "held");
	}

	private static boolean flag(final JsonObject m, final String name) {
		return m.get(name) instanceof JsonPrimitive v && v.isBoolean() && v.getAsBoolean();
	}

	/** The host's Space and Left Shift (key "jump"/"sprint"): Cyberpunk keeps them too, Minecraft only reads them. Client thread. */
	private static boolean jumpDown, sprintDown;
	/** A jump press not yet taken by the glide start. Client thread. */
	private static boolean jumpPressed;

	static void setKey(final String k, final boolean down) {
		if (k.equals("jump")) {
			jumpPressed |= down && !jumpDown;
			jumpDown = down;
		} else if (k.equals("sprint")) {
			sprintDown = down;
		}
	}

	/** Client thread: whether the host's jump key is held. */
	public static boolean jumpDown() {
		return jumpDown;
	}

	/** Client thread: whether the host's sprint key is held. */
	public static boolean sprintDown() {
		return sprintDown;
	}

	/** Client thread: true once per press of the host's jump key since the last call. */
	public static boolean takeJumpPress() {
		boolean pressed = jumpPressed;
		jumpPressed = false;
		return pressed;
	}

	/** The host went away: no key stays held, and V is neither crouching nor held. Client thread. */
	static void resetKeys() {
		jumpDown = sprintDown = jumpPressed = false;
		Bridge.hostCrouching = false;
		Bridge.hostHeld = false;
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

	/** Metres from the host camera to the Night City surface under the crosshair; negative: none in range. */
	private static volatile double aim = -1.0;

	/** {"t":"aim","d":metres} — d &lt; 0 means no Night City surface within the host's ray. */
	static void updateAim(final JsonObject m) {
		double d = number(m.get("d"), "aim", -1.0, 64.0);
		aim = d < 0.0 ? -1.0 : d;
	}

	/**
	 * Reach limited to the Night City surface under the crosshair, so blocks behind a Cyberpunk wall are never targeted
	 * (no outline through walls, no building or breaking through them).
	 */
	public static double aimLimit(final double reach) {
		double d = aim;
		return d < 0.0 ? reach : Math.min(reach, d);
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
