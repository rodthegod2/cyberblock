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
	VEH_LOCK(Target.VEHICLE, null, 2, 0),
	VEH_ENGINE_OFF(Target.VEHICLE, null, 2, 0),
	VEH_BRAKE(Target.VEHICLE, null, 2, 0),
	VEH_START(Target.VEHICLE, null, 2, 0),
	VEH_JAM(Target.VEHICLE, null, 2, 0),
	/** A district's traffic lights switch (the host's current district). */
	TRAFFIC_SWITCH(Target.DISTRICT, Permissions.DeviceType.TRAFFIC, 10, 0),
	/** The cars on the road segment at a point stop for a minute. */
	GRIDLOCK(Target.POINT, Permissions.DeviceType.TRAFFIC, 60, 60),
	/** A district's lights and devices off for minutes (1 to 5), then back. */
	BLACKOUT(Target.DISTRICT, Permissions.DeviceType.LIGHT, 30, 120),
	/** Recon: the camera's place and facing, so Minecraft renders the replica from there. */
	CAM_FEED(Target.CAMERA, Permissions.DeviceType.CAMERA, 2, 0),
	/** The figures around a point are outlined for the asker's team. */
	OUTLINE(Target.POINT, null, 10, 20),
	/** Night City's own ping around a point. */
	PING_AREA(Target.POINT, null, 5, 10);

	/** What a request names. */
	public enum Target {
		VEHICLE, CAMERA, DISTRICT, POINT
	}

	/** Blackout length bounds in seconds (1 to 5 minutes). */
	public static final int MIN_BLACKOUT = 60, MAX_BLACKOUT = 300;
	/** Radius in blocks of the point hacks (the cars of one road segment, the figures of one block, a ping). */
	public static final double GRIDLOCK_RADIUS = 24.0, OUTLINE_RADIUS = 24.0, PING_RADIUS = 32.0;

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

	/** The device type whose bit (on top of the `devices` toggle) gates a guest's use; null: the `hacks` toggle gates it. */
	public Permissions.@Nullable DeviceType device() {
		return this.device;
	}

	/** How long one player waits between two of these. */
	public int cooldownSeconds() {
		return this.cooldownSeconds;
	}

	/** How long the effect lasts (0: instant or the scripts' own time); a blackout's is the request's. */
	public int seconds() {
		return this.seconds;
	}

	/** The radius of a point hack (0 for the others). */
	public double radius() {
		return switch (this) {
			case GRIDLOCK -> GRIDLOCK_RADIUS;
			case OUTLINE -> OUTLINE_RADIUS;
			case PING_AREA -> PING_RADIUS;
			default -> 0.0;
		};
	}

	/** The lang key of its name, cyberblock.rhack.<wire>. */
	public String langKey() {
		return "cyberblock.rhack." + this.wire();
	}

	/** Plain English name (the fallback of {@link #langKey}). */
	public String english() {
		return switch (this) {
			case VEH_LOCK -> "Lock doors";
			case VEH_ENGINE_OFF -> "Kill engine";
			case VEH_BRAKE -> "Force brake";
			case VEH_START -> "Remote start";
			case VEH_JAM -> "Jam";
			case TRAFFIC_SWITCH -> "Switch traffic lights";
			case GRIDLOCK -> "Gridlock";
			case BLACKOUT -> "Blackout";
			case CAM_FEED -> "Camera feed";
			case OUTLINE -> "Outline";
			case PING_AREA -> "Ping";
		};
	}

	/**
	 * May this request reach the owner's game? The owner itself and other hosts always may (section 1.6, Permissions.allows); a
	 * guest needs the `hacks` toggle, or for a device hack the `devices` toggle and the device type's bit.
	 */
	public boolean allowed(final Permissions perms, final boolean ownerOrHost) {
		if (ownerOrHost) {
			return true;
		}

		return this.device == null ? perms.has(Permissions.Toggle.HACKS) : perms.device(this.device);
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
