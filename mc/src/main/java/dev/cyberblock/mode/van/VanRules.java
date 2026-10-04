package dev.cyberblock.mode.van;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.hack.actions.RemoteHack;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * The Hacker in the Van's rules (megaplan task 8-V1), pure: which hosts a Van links to, what its screen shows of the host's area,
 * how a drone flies (speed, its leash to the Van, the battery), what a drone sees, and which actions the Hack Wheel offers on a
 * person, a car or a device. Distances are in blocks; times in server ticks.
 */
public final class VanRules {
	/** A Van links to a host whose V is at most this far away (horizontally): the Van works in the field, not across the map. */
	public static final double LINK_RANGE = 512.0;
	/** The screen's view of the host's area: figures and devices this close to V (horizontally; Night City streams NPCs within 48 m). */
	public static final double AREA_RADIUS = 48.0;
	/** The miniature's radius around V (the Holo-Table renderer, scoped). */
	public static final double MINI_RADIUS = 64.0;
	/** A drone stays within this distance of its Van (a sphere); past it the drone is held at the edge. */
	public static final double DRONE_RANGE = 64.0;
	/** A drone sees figures and devices this close with a clear line of sight. */
	public static final double DRONE_SIGHT = 32.0;
	/** Horizontal top speed (blocks a tick: 10 m/s) and climb speed. */
	public static final double DRONE_SPEED = 0.5, DRONE_CLIMB = 0.35;
	/** How fast the drone reaches the speed its keys ask for (share of the difference a tick). */
	static final double ACCEL = 0.35;
	/** A full battery: 90 s of flight. It recharges in the Van three times as fast (30 s from empty). */
	public static final int BATTERY = 1800, RECHARGE = 3;
	/** A drone may take off with at least this much charge (20 %). */
	public static final int LAUNCH_MIN = BATTERY / 5;
	/** At most this many targets in one screen state. */
	public static final int MAX_TARGETS = 64;

	/** What a target is: a person (an NPC figure), a car (a vehicle figure) or a device. */
	public enum Kind {
		PERSON("person"), CAR("car"), DEVICE("device");

		private final String wire;

		Kind(final String wire) {
			this.wire = wire;
		}

		public String wire() {
			return this.wire;
		}

		public static Kind byWire(final String wire) {
			for (Kind k : values()) {
				if (k.wire.equals(wire)) {
					return k;
				}
			}

			throw new IllegalArgumentException("unknown target kind " + wire);
		}
	}

	/** Where the netrunner sees a target from: the Van's screen (the host's area) or the drone. */
	public enum Seen {
		SCREEN("screen"), DRONE("drone");

		private final String wire;

		Seen(final String wire) {
			this.wire = wire;
		}

		public String wire() {
			return this.wire;
		}
	}

	/** The keys a pilot holds (vanilla's player input while seated in the Van): Ctrl is down, Shift leaves the Van as in any seat. */
	public record Keys(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down) {
		public static final Keys NONE = new Keys(false, false, false, false, false, false);
	}

	/** One Hack Wheel entry: what it does, as the wire names it. */
	public sealed interface Option permits Quickhack, Vehicle, Area, DeviceAct {
	}

	/** A quickhack chip (the netrunner's own, paid with his RAM). */
	public record Quickhack(Hack hack) implements Option {
	}

	/** One of the Vehicle Panel's hacks on a car. */
	public record Vehicle(RemoteHack hack) implements Option {
	}

	/** An area hack around the target (outline its neighbours for the team, Night City's ping). */
	public record Area(RemoteHack hack) implements Option {
	}

	/** A device action (devact). */
	public record DeviceAct(String act) implements Option {
	}

	private VanRules() {
	}

	/** Horizontal distance. */
	public static double flat(final Vec3 a, final Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Can a Van at `van` link to a host whose V stands at `v`? */
	public static boolean links(final Vec3 van, final Vec3 v) {
		return flat(van, v) <= LINK_RANGE;
	}

	/** Is a target at `p` in the host's area the Van's screen shows (V at `v`)? */
	public static boolean inArea(final Vec3 p, final Vec3 v) {
		return flat(p, v) <= AREA_RADIUS;
	}

	/** Is a target at `p` close enough to the drone's eye to be seen (line of sight is the caller's)? */
	public static boolean inSight(final Vec3 p, final Vec3 eye) {
		return p.distanceToSqr(eye) <= DRONE_SIGHT * DRONE_SIGHT;
	}

	/**
	 * The drone's velocity after one tick: toward the speed its keys ask for (forward and sideways along the pilot's yaw, up and down
	 * straight), never faster than the top speeds. Yaw in degrees as Minecraft's (0 = south, +z).
	 */
	public static Vec3 step(final Vec3 velocity, final Keys keys, final float yawDegrees) {
		double f = (keys.forward() ? 1 : 0) - (keys.back() ? 1 : 0), s = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		double yaw = Math.toRadians(yawDegrees), sin = Math.sin(yaw), cos = Math.cos(yaw);
		// Minecraft's forward is (-sin, cos); its left is (cos, sin)
		double tx = -sin * f + cos * s, tz = cos * f + sin * s;
		double len = Math.sqrt(tx * tx + tz * tz);
		if (len > 1e-9) {
			tx = tx / len * DRONE_SPEED;
			tz = tz / len * DRONE_SPEED;
		}

		double ty = ((keys.up() ? 1 : 0) - (keys.down() ? 1 : 0)) * DRONE_CLIMB;
		Vec3 next = velocity.add(new Vec3(tx, ty, tz).subtract(velocity).scale(ACCEL));
		double h = Math.sqrt(next.x * next.x + next.z * next.z);
		if (h > DRONE_SPEED) {
			next = new Vec3(next.x / h * DRONE_SPEED, next.y, next.z / h * DRONE_SPEED);
		}

		return new Vec3(next.x, Math.clamp(next.y, -DRONE_CLIMB, DRONE_CLIMB), next.z);
	}

	/** The drone's place held within its leash: a point past DRONE_RANGE from the Van comes back to the edge. */
	public static Vec3 leash(final Vec3 drone, final Vec3 van) {
		Vec3 d = drone.subtract(van);
		double len = d.length();
		return len <= DRONE_RANGE ? drone : van.add(d.scale(DRONE_RANGE / len));
	}

	/** The battery after one tick: draining while the drone flies, recharging in the Van. */
	public static int battery(final int charge, final boolean flying) {
		return flying ? Math.max(0, charge - 1) : Math.min(BATTERY, charge + RECHARGE);
	}

	/** May a drone take off with this charge? */
	public static boolean mayLaunch(final int charge) {
		return charge >= LAUNCH_MIN;
	}

	/** The charge as a share 0..1. */
	public static float share(final int charge) {
		return Math.clamp(charge / (float) BATTERY, 0.0F, 1.0F);
	}

	/**
	 * The Hack Wheel on a target (the full wheel: megaplan 8-V1): on a person every quickhack chip but the device lever, then the area
	 * hacks around them; on a car the Vehicle Panel's hacks, then the chips (they reach its driver); on a device what that device type
	 * takes, then the lever chip.
	 */
	public static List<Option> options(final Kind kind, final DeviceType device) {
		List<Option> out = new ArrayList<>();
		switch (kind) {
			case PERSON -> {
				chips(out);
				out.add(new Area(RemoteHack.OUTLINE));
				out.add(new Area(RemoteHack.PING_AREA));
			}
			case CAR -> {
				for (RemoteHack h : RemoteHack.values()) {
					if (h.target() == RemoteHack.Target.VEHICLE) {
						out.add(new Vehicle(h));
					}
				}

				chips(out);
			}
			case DEVICE -> {
				for (String act : deviceActs(device)) {
					out.add(new DeviceAct(act));
				}

				out.add(new Quickhack(Hack.DEVICE));
			}
		}

		return out;
	}

	private static void chips(final List<Option> out) {
		for (Hack h : Hack.values()) {
			if (!h.needsDevice()) {
				out.add(new Quickhack(h));
			}
		}
	}

	/**
	 * The Night City acts a device type takes from the Van (the section 1.9 amendment's list; the vending shop, Breach and lift calls
	 * are Minecraft-side or unproven, so not here). Cameras and turrets use on/off (a disabled device reads as quest-locked).
	 */
	public static List<String> deviceActs(final DeviceType type) {
		return switch (type) {
			case DOOR, GATE -> List.of("open", "close", "lock", "unlock");
			case LIGHT, CAMERA -> List.of("on", "off");
			case TURRET -> List.of("off", "on", "friendly");
			case SCREEN -> List.of("distract");
			case TRAFFIC -> List.of("toggle");
			case TERMINAL, VENDING, ELEVATOR, CONTAINER -> List.of();
		};
	}

	/** Is this one of the acts the Van may ask of that device type? */
	public static boolean deviceActAllowed(final DeviceType type, final String act) {
		return deviceActs(type).contains(act);
	}
}
