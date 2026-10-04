package dev.cyberblock.hack.actions;

import dev.cyberblock.net.Permissions;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The interaction map's remote hacks (megaplan task 6-I1, section 1.9 row 6 `rhack`): the wire name, what the request must name
 * (a car figure, a camera, the host's district or a point), the guest toggle that gates it (section 1.6: `hacks`, or `devices`
 * plus the device type's bit), how often one player may send it and how long its effect lasts in Night City. Pure.
 */
public enum RemoteHack {
	VEH_LOCK(Target.VEHICLE, null, 2, 30),
	VEH_ENGINE_OFF(Target.VEHICLE, null, 2, 30),
	VEH_BRAKE(Target.VEHICLE, null, 2, 30),
	VEH_START(Target.VEHICLE, null, 2, 30),
	VEH_JAM(Target.VEHICLE, null, 2, 30),
	/** The traffic lights around the host's V switch for a minute. */
	TRAFFIC_SWITCH(Target.DISTRICT, Permissions.DeviceType.TRAFFIC, 10, 60),
	/** The cars on the road segment at a point stop for a minute. */
	GRIDLOCK(Target.POINT, Permissions.DeviceType.TRAFFIC, 60, 60),
	/** The lights and devices around the host's V off for minutes (1 to 5, two when the request names none), then back. */
	BLACKOUT(Target.DISTRICT, Permissions.DeviceType.LIGHT, 30, 120),
	/** Recon: the camera's place and facing, so Minecraft renders the replica from there. */
	CAM_FEED(Target.CAMERA, Permissions.DeviceType.CAMERA, 2, 0),
	/** The figures around a point are outlined for the asker's team. */
	OUTLINE(Target.POINT, null, 10, 20),
	/** Night City's own ping around a point (a one-shot: `secs` 0). */
	PING_AREA(Target.POINT, null, 5, 0);

	/** What a request names. */
	public enum Target {
		VEHICLE, CAMERA, DISTRICT, POINT
	}

	/** Blackout length bounds in seconds (1 to 5 minutes). */
	public static final int MIN_BLACKOUT = 60, MAX_BLACKOUT = 300;
	/** Every vehicle effect is timed (shared/protocol.md: "the server sends 30"); the scripts put the car back after it. */
	public static final int VEHICLE_SECONDS = 30;
	/** How long the Minecraft-side ping marker of a `ping_area` shows (Night City's ping itself is a one-shot). */
	public static final int PING_MARK_SECONDS = 10;
	/**
	 * Radius in metres of the area hacks, a whole number 1..150 on the wire: the cars of one road segment, the figures of one block, a
	 * ping, and the 150 m around V of the district hacks (the scripts' written fallback: the Phase 4 devices within 150 m of V).
	 */
	public static final int GRIDLOCK_RADIUS = 24, OUTLINE_RADIUS = 24, PING_RADIUS = 32, DISTRICT_RADIUS = 150;

	private final Target target;
	private final Permissions.@Nullable DeviceType device;
	private final int cooldownSeconds;
	private final int seconds;

	RemoteHack(final Target target, final Permissions.@Nullable DeviceType device, final int cooldownSeconds, final int seconds) {
		this.target = target;
		this.device = device;
		this.cooldownSeconds = cooldownSeconds;
		this.seconds = seconds;
	}

	/** The `rhack.hack` value, e.g. "veh_engine_off". */
	public String wire() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public Target target() {
		return this.target;
	}

	/** The device type whose bit (on top of the `hacks` and `devices` toggles) gates a guest's use; null: only `hacks` gates it. */
	public Permissions.@Nullable DeviceType device() {
		return this.device;
	}

	/** How long one player waits between two of these. */
	public int cooldownSeconds() {
		return this.cooldownSeconds;
	}

	/** The DOWN `rhack.secs`: how long the effect lasts in Night City (0: a one-shot, or none sent); a blackout's default length. */
	public int seconds() {
		return this.seconds;
	}

	/** The `secs` the server sends: the length the player picked for a blackout, this hack's own for the others. */
	public int downSeconds(final int requested) {
		return this == BLACKOUT ? requested : this.seconds;
	}

	/** An area hack (shared/protocol.md "Area hacks"): `target` null and always `p`, `r` and `secs`. */
	public boolean area() {
		return this.target == Target.DISTRICT || this.target == Target.POINT;
	}

	/** The `rhack.r` of an area hack in metres (0 for the others). */
	public int radius() {
		return switch (this) {
			case GRIDLOCK -> GRIDLOCK_RADIUS;
			case OUTLINE -> OUTLINE_RADIUS;
			case PING_AREA -> PING_RADIUS;
			case TRAFFIC_SWITCH, BLACKOUT -> DISTRICT_RADIUS;
			default -> 0;
		};
	}

	/** The lang key of its name: the lang owner's (6A) button keys, cyberblock.hack.vehicle|traffic|netmap.*. */
	public String langKey() {
		return switch (this) {
			case VEH_LOCK -> "cyberblock.hack.vehicle.lock";
			case VEH_ENGINE_OFF -> "cyberblock.hack.vehicle.engine_off";
			case VEH_BRAKE -> "cyberblock.hack.vehicle.brake";
			case VEH_START -> "cyberblock.hack.vehicle.start";
			case VEH_JAM -> "cyberblock.hack.vehicle.jam";
			case TRAFFIC_SWITCH -> "cyberblock.hack.traffic.switch";
			case GRIDLOCK -> "cyberblock.hack.traffic.gridlock";
			case BLACKOUT -> "cyberblock.hack.netmap.blackout";
			case CAM_FEED -> "cyberblock.hack.netmap.cameras";
			case OUTLINE -> "cyberblock.hack.netmap.outline";
			case PING_AREA -> "cyberblock.hack.netmap.ping";
		};
	}

	/** Plain English name (the fallback of {@link #langKey}, the same text as en_us.json). */
	public String english() {
		return switch (this) {
			case VEH_LOCK -> "Lock doors";
			case VEH_ENGINE_OFF -> "Kill engine";
			case VEH_BRAKE -> "Force brake";
			case VEH_START -> "Remote start";
			case VEH_JAM -> "Jam";
			case TRAFFIC_SWITCH -> "Switch the lights";
			case GRIDLOCK -> "Gridlock this road";
			case BLACKOUT -> "Black out district";
			case CAM_FEED -> "Camera feeds";
			case OUTLINE -> "Outline people";
			case PING_AREA -> "Ping area";
		};
	}

	/**
	 * May this request reach the owner's game? The owner itself and other hosts always may (section 1.6, Permissions.allows); a
	 * guest always needs the `hacks` toggle (it gates quickhacks on the host's NPCs, cars and devices), and for a device hack also
	 * the `devices` toggle and the device type's bit.
	 */
	public boolean allowed(final Permissions perms, final boolean ownerOrHost) {
		if (ownerOrHost) {
			return true;
		}

		return perms.has(Permissions.Toggle.HACKS) && (this.device == null || perms.device(this.device));
	}

	public static @Nullable RemoteHack byWire(final @Nullable String wire) {
		for (RemoteHack h : values()) {
			if (h.wire().equals(wire)) {
				return h;
			}
		}

		return null;
	}
}
