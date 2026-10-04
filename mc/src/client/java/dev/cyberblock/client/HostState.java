// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.client.net.HState;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

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
	 * @param seat V sits in a vehicle (cam "seat", plan retest3 section 1.1): px/py/pz are then V's hips, not the feet
	 */
	public record Pose(
		long hostFrame, double x, double y, double z, float yaw, float pitch, float roll, float fov,
		boolean firstPerson, double px, double py, double pz, float bodyYaw, long receivedNanos,
		boolean drive, float lookYaw, float lookPitch, boolean gun,
		boolean fly, boolean menu, boolean air, boolean held, boolean seat
	) {
	}

	private static final long TIMEOUT_NANOS = 2_000_000_000L;
	/** Positions beyond these are garbage, not a place in the world: rejected (the server clamps moves to 3e7 horizontally). */
	private static final double XZ_LIMIT = 2.9E7;
	private static final double Y_LIMIT = 20000.0;
	private static volatile Pose latest;
	/** Cyberpunk says V is in a vehicle: Steve sits where V sits (cam.seat), or is hidden until the host says where that is. */
	private static volatile boolean vehicle;
	/** The pose this frame renders with, taken once per frame so every hook agrees. Render thread only. */
	private static Pose frame;
	/**
	 * What the server once read from shared statics (megaplan section 1.2, now sent as {@code hstate}): V crouched (cam.cr), held by
	 * the game (cam.held), in the air (cam.air), standing still on the ground, V's feet in Cyberpunk z (NaN before the first cam) and
	 * the cam's lv. Any thread reads them.
	 */
	private static volatile boolean crouching, held, airborne, stands;
	private static volatile double feetCpZ = Double.NaN;
	private static volatile int camLevel;
	/** The ground alignment k of the world this client plays in, from the server's last {@code align} (0 before one). */
	private static volatile int alignK;
	/** Host jump key presses since start (monotonic): readers keep their own last value; nobody consumes it. */
	private static final AtomicInteger jumpPresses = new AtomicInteger();
	/** Cyberpunk is driving this client's camera: a live pose for the frame being rendered. Any thread reads it. */
	private static volatile boolean active;

	private HostState() {
	}

	/**
	 * {"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"fp":bool,"pl":[x,y,z],"h":bodyYaw}, plus the flags
	 * "cr", "fly", "menu", "air", "held" and "seat" (false when absent or not a boolean: an older host never sends them). Every number is
	 * checked before the pose is built, and a bad message throws IllegalArgumentException (the link logs it and carries on, the
	 * previous pose stays): NaN or a position outside the world would reach player.setPos, whose move packet gets the player
	 * kicked by the server (that ends the singleplayer session). Pitch is clamped to +-90: the host's asin(-dy) can round past it.
	 *
	 * <p>"lv" (plan retest2 section 1.2) is the ground alignment k (0..63) the host converted this cam with: Minecraft y = Cyberpunk
	 * z - k/64. Until the host has applied a new k (an align is on its way), its y is off by (lv - k)/64, so both y values are
	 * corrected to this world's k (alignK, from the server's align). Absent (fakehost, an older host): taken to be this world's k.
	 */
	static void update(final JsonObject m) {
		JsonArray p = m.getAsJsonArray("p");
		JsonArray r = m.getAsJsonArray("r");
		JsonArray pl = m.has("pl") ? m.getAsJsonArray("pl") : p;
		int k = alignK;
		int lv = m.has("lv") ? level(m.get("lv")) : k;
		double dy = (lv - k) / LEVEL_STEPS;
		double x = coordinate(p, 0, "p.x"), y = coordinate(p, 1, "p.y") + dy, z = coordinate(p, 2, "p.z");
		float yaw = angle(r.get(0), "yaw");
		float pitch = Math.clamp(angle(r.get(1), "pitch"), -90.0F, 90.0F);
		float roll = r.size() > 2 ? angle(r.get(2), "roll") : 0.0F;
		float fov = m.has("fov") ? (float) number(m.get("fov"), "fov", 1.0, 179.0) : 70.0F;
		double px = coordinate(pl, 0, "pl.x"), rawPy = coordinate(pl, 1, "pl.y"), pz = coordinate(pl, 2, "pl.z");
		double py = rawPy + dy;
		float bodyYaw = m.has("h") ? angle(m.get("h"), "h") : yaw;
		float lookYaw = yaw;
		float lookPitch = pitch;
		if (m.has("look")) {
			JsonArray look = m.getAsJsonArray("look");
			lookYaw = angle(look.get(0), "look yaw");
			lookPitch = Math.clamp(angle(look.get(1), "look pitch"), -90.0F, 90.0F);
		}

		boolean fly = flag(m, "fly"), air = flag(m, "air"), held = flag(m, "held");
		latest = new Pose(
			m.has("f") ? m.get("f").getAsLong() : 0L,
			x, y, z, yaw, pitch, roll, fov,
			!m.has("fp") || m.get("fp").getAsBoolean(),
			px, py, pz, bodyYaw,
			System.nanoTime(),
			m.has("drive") && m.get("drive").getAsBoolean(),
			lookYaw, lookPitch,
			m.has("gun") && m.get("gun").getAsBoolean(),
			fly, flag(m, "menu"), air, held, flag(m, "seat")
		);
		// V crouched (layers 3-6): Steve crouches too (KeyboardInputMixin), and the cyber track reads it for optical camo
		crouching = flag(m, "cr");
		// V cannot be moved: the server refuses ender pearl teleports (ThrownEnderpearlMixin)
		HostState.held = held;
		// the ground alignment (GroundAlign) is chosen from V's feet in Cyberpunk z, while V stands (plan retest2 R4)
		feetCpZ = rawPy + lv / LEVEL_STEPS;
		camLevel = lv;
		stands = !air && !held && !fly;
		airborne = air;
	}

	private static boolean flag(final JsonObject m, final String name) {
		return m.get(name) instanceof JsonPrimitive v && v.isBoolean() && v.getAsBoolean();
	}

	/** Steps per metre of the ground alignment: offset = k / 64 m (plan retest2 section 1.1). */
	private static final double LEVEL_STEPS = 64.0;

	/** cam.lv: an integer 0..63, else the cam is rejected like any other bad number. */
	private static int level(final JsonElement e) {
		double v = number(e, "lv", 0.0, 63.0);
		if (v != Math.rint(v)) {
			throw new IllegalArgumentException("lv is not an integer: " + v);
		}

		return (int) v;
	}

	/** The host's Space and Left Shift (key "jump"/"sprint"): Cyberpunk keeps them too, Minecraft only reads them. Client thread. */
	private static boolean jumpDown, sprintDown;
	/** A jump press not yet taken by the glide start. Client thread. */
	private static boolean jumpPressed;

	static void setKey(final String k, final boolean down) {
		if (k.equals("jump")) {
			if (down && !jumpDown) {
				jumpPressed = true;
				jumpPresses.incrementAndGet(); // the cyber track's legs VFX keep their own last value
			}

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

	/** The host went away: no key stays held, and V is neither crouching, held, standing nor in the air (nothing is known of V). Client thread. */
	static void resetKeys() {
		jumpDown = sprintDown = jumpPressed = false;
		resetAim();
		crouching = held = stands = airborne = false;
		feetCpZ = Double.NaN;
	}

	/** V is crouched (cam.cr): Steve crouches too (KeyboardInputMixin), and the cyber track reads it for optical camo. */
	public static boolean crouching() {
		return crouching;
	}

	/** The game holds V (cam.held). */
	public static boolean held() {
		return held;
	}

	/** V is in the air (cam.air). */
	public static boolean airborne() {
		return airborne;
	}

	/** V stands still on the ground: !air && !held && !fly. */
	public static boolean stands() {
		return stands;
	}

	/** V's feet in Cyberpunk z (cam.pl y + lv/64); NaN before the first cam and after the host went away. */
	public static double feetCpZ() {
		return feetCpZ;
	}

	/** Host jump key presses since start. */
	public static int jumpPresses() {
		return jumpPresses.get();
	}

	public static int alignK() {
		return alignK;
	}

	/** The server's {@code align} k for this world (ClientNet sees it on its way to the DLL); 0 off a server. */
	public static void setAlignK(final int k) {
		alignK = Math.clamp(k, 0, (int) LEVEL_STEPS - 1);
	}

	/** Cyberpunk drives the camera (a live pose this frame). */
	public static boolean active() {
		return active;
	}

	/** The {@code hstate} sample for the server (megaplan section 1.2); {@code launch} comes from Flight. */
	public static HState.Snapshot hstate(final boolean launch) {
		Pose p = live();
		boolean on = p != null;
		return new HState.Snapshot(on, vehicle, on && airborne, on && crouching, on && held, on && p.fly(), launch, on && p.seat(),
			camLevel, on ? feetCpZ : Double.NaN);
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

	/**
	 * How far past the Night City surface under the crosshair Minecraft may still target (plan Q-B1). The host's ray stops on a
	 * face that is the very face of the block being looked at (a placed block's collision box, a street whose floor the ground
	 * alignment puts exactly on a cell boundary), and its distance comes rounded to the millimetre: a pick ending exactly there
	 * misses that face about half the time, so cracks never grew and outline blocks could not be broken.
	 */
	static final double AIM_MARGIN = 0.35;
	/** A shorter aim distance is believed after this many lower samples in a row... */
	static final int AIM_FALL_SAMPLES = 3;
	/** ...or once the first of them is this old (the host sends aim on change only, so a steady lower value sends no more). */
	static final long AIM_FALL_NANOS = 300_000_000L;
	/** While attack is held, the block being broken stays targeted this long after the limit last reached it. */
	static final long STICKY_NANOS = 500_000_000L;

	private static final Object AIM_LOCK = new Object();
	/** The host's latest aim distance; +infinity: no Night City surface within its ray. Guarded by AIM_LOCK. */
	private static double aimRaw = Double.POSITIVE_INFINITY;
	/** The distance the limit uses: it rises at once and falls only once a shorter one has held (see AIM_FALL_*). */
	private static double aimSmoothed = Double.POSITIVE_INFINITY;
	/** The latest of the shorter samples in a row, how many there were, and when the first came. */
	private static double aimLow;
	private static int aimLowCount;
	private static long aimLowSince;
	/** The limit the last pick used (the aim log reports it). */
	private static volatile double lastLimit = Double.NaN;

	/** {"t":"aim","d":metres} — d &lt; 0 means no Night City surface within the host's ray. */
	static void updateAim(final JsonObject m) {
		updateAim(m, System.nanoTime());
	}

	static void updateAim(final JsonObject m, final long now) {
		double d = number(m.get("d"), "aim", -1.0, 64.0);
		double v = d < 0.0 ? Double.POSITIVE_INFINITY : d;
		synchronized (AIM_LOCK) {
			aimRaw = v;
			if (v >= aimSmoothed) {
				aimSmoothed = v;
				aimLowCount = 0;
				return;
			}

			if (aimLowCount == 0) {
				aimLowSince = now;
			}

			aimLow = v;
			if (++aimLowCount >= AIM_FALL_SAMPLES || now - aimLowSince >= AIM_FALL_NANOS) {
				aimSmoothed = v;
				aimLowCount = 0;
			}
		}
	}

	/** The host went away or a new world starts: no limit until the host says again. */
	static void resetAim() {
		synchronized (AIM_LOCK) {
			aimRaw = aimSmoothed = Double.POSITIVE_INFINITY;
			aimLowCount = 0;
		}

		stickyPos = null;
	}

	/**
	 * Reach limited to the Night City surface under the crosshair plus AIM_MARGIN, so blocks behind a Cyberpunk wall are never
	 * targeted (no outline through walls, no building or breaking through them) while the block in front of it always is.
	 */
	public static double aimLimit(final double reach) {
		return aimLimit(reach, System.nanoTime());
	}

	static double aimLimit(final double reach, final long now) {
		double limit;
		synchronized (AIM_LOCK) {
			if (aimLowCount > 0 && now - aimLowSince >= AIM_FALL_NANOS) {
				aimSmoothed = aimLow;
				aimLowCount = 0;
			}

			limit = Math.min(reach, aimSmoothed + AIM_MARGIN);
		}

		lastLimit = limit;
		return limit;
	}

	/** The host's latest aim distance in metres, -1 when it has none (the aim log). */
	public static double aimRaw() {
		synchronized (AIM_LOCK) {
			return Double.isInfinite(aimRaw) ? -1.0 : aimRaw;
		}
	}

	/** The limit the last pick used, NaN before the first (the aim log). */
	public static double lastLimit() {
		return lastLimit;
	}

	/** The block the pick last found within the limit while attack was held, and when (client thread). */
	private static BlockPos stickyPos;
	private static long stickySeen;

	/** Client thread, after each pick within the limit: pos is the block it found (null: none, or not a block). */
	public static void picked(final @Nullable BlockPos pos, final boolean attackDown, final long now) {
		if (!attackDown) {
			stickyPos = null;
		} else if (pos != null) {
			stickyPos = pos;
			stickySeen = now;
		}
	}

	/**
	 * Client thread: the block to keep targeting although the limit no longer reaches it (plan Q-B1): the one being broken, while
	 * attack is still held, the block is still there and the limit reached it less than STICKY_NANOS ago. Null otherwise (and the
	 * stickiness ends).
	 */
	public static @Nullable BlockPos stickyTarget(final boolean attackDown, final Predicate<BlockPos> exists, final long now) {
		BlockPos pos = stickyPos;
		if (pos == null) {
			return null;
		}

		if (!attackDown || now - stickySeen > STICKY_NANOS || !exists.test(pos)) {
			stickyPos = null;
			return null;
		}

		return pos;
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
		active = frame != null;
	}
}
