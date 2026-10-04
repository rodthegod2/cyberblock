package dev.cyberblock.device;

import dev.cyberblock.net.Permissions.DeviceType;

/**
 * One Night City device a host reported (a {@code devices} entry, megaplan section 1.9 and its Phase 0 amendment): its id (scoped by
 * its owner), Cyberpunk class, type, Minecraft position and yaw, state ({@code EDeviceStatus} -2..1, or {@code EDoorStatus} -2..1 for
 * doors) and flags. Immutable.
 *
 * @param idString whether the host sent the id as a decimal string (the devact DOWN echoes it the same way)
 */
public record Device(long id, boolean idString, String cls, DeviceType type, double x, double y, double z, float yaw, int st, int fl) {
	/** fl bits (section 1.9). */
	public static final int LOCKED = 1, SECURE = 2, QUEST_LOCKED = 4, BROKEN = 8, POWERED = 16;
	/** st values: EDeviceStatus DISABLED, UNPOWERED, OFF, ON; EDoorStatus SEALED, LOCKED, CLOSED, OPENED. */
	public static final int ST_DISABLED = -2, ST_UNPOWERED = -1, ST_OFF = 0, ST_ON = 1;
	public static final int DOOR_SEALED = -2, DOOR_LOCKED = -1, DOOR_CLOSED = 0, DOOR_OPENED = 1;

	public Device withState(final int newSt, final int newFl) {
		return new Device(this.id, this.idString, this.cls, this.type, this.x, this.y, this.z, this.yaw, newSt, newFl);
	}

	/** The same device at its newest place and state (a later list entry). */
	public boolean samePlace(final Device o) {
		return this.x == o.x && this.y == o.y && this.z == o.z && this.yaw == o.yaw && this.type == o.type && this.cls.equals(o.cls);
	}

	public boolean on() {
		return this.st == ST_ON;
	}

	/** A door that is open, or a gate whose state is ON (open). */
	public boolean open() {
		return this.st == DOOR_OPENED;
	}

	public boolean locked() {
		return (this.fl & LOCKED) != 0 || this.type == DeviceType.DOOR && this.st == DOOR_LOCKED;
	}

	public boolean secure() {
		return (this.fl & SECURE) != 0;
	}

	/** Quest- or cutscene-locked: a sealed door or a disabled device (fl bit 4); shown greyed, never used from Minecraft. */
	public boolean questLocked() {
		return (this.fl & QUEST_LOCKED) != 0;
	}

	public boolean broken() {
		return (this.fl & BROKEN) != 0;
	}

	/** The id as the protocol writes it (unsigned decimal). */
	public String idText() {
		return Long.toUnsignedString(this.id);
	}
}
