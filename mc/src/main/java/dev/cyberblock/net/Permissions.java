package dev.cyberblock.net;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What guests may do to one host's Cyberpunk game (megaplan section 1.6). Immutable. The host's own actions, actions with no player
 * behind them and actions of other hosts are always allowed; a toggle gates only what a guest causes. A gated action still happens in
 * the Minecraft world; it just does not reach that host's game (the caller shows {@code cyberblock.guest.blocked} to the guest).
 * Stored per host UUID in HostsData; changed with {@code /cyberblock guests <toggle> on|off} or the {@code cyberblock:perms}
 * payload.
 */
public record Permissions(int bits, int devices) {
	public enum Toggle {
		EXPLOSIONS(Payloads.PERM_EXPLOSIONS),
		DAMAGE(Payloads.PERM_DAMAGE),
		HACKS(Payloads.PERM_HACKS),
		DEVICES(Payloads.PERM_DEVICES),
		SPAWNS(Payloads.PERM_SPAWNS);

		private final int bit;

		Toggle(final int bit) {
			this.bit = bit;
		}

		public int bit() {
			return this.bit;
		}

		/** The command and settings name: explosions, damage, hacks, devices, spawns. */
		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Toggle byId(final String id) {
			for (Toggle t : values()) {
				if (t.id().equals(id)) {
					return t;
				}
			}

			return null;
		}
	}

	/**
	 * The Phase 4 device types and their bits (megaplan section 1.11), each on top of the {@code devices} toggle. The id is the
	 * {@code devices.kind} value, the command name ({@code /cyberblock guests devices <type> on|off}) and the device_map kind.
	 */
	public enum DeviceType {
		DOOR(1), GATE(2), LIGHT(4), CAMERA(8), TERMINAL(16), VENDING(32), TRAFFIC(64), ELEVATOR(128), TURRET(256), SCREEN(512), CONTAINER(1024);

		private final int bit;

		DeviceType(final int bit) {
			this.bit = bit;
		}

		public int bit() {
			return this.bit;
		}

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable DeviceType byId(final String id) {
			for (DeviceType t : values()) {
				if (t.id().equals(id)) {
					return t;
				}
			}

			return null;
		}
	}

	/** Every toggle bit. */
	public static final int ALL = 31;
	/** Phase 4's 11 device types (section 1.11), all allowed by default. */
	public static final int ALL_DEVICES = 0x7FF;
	/** explosions, damage, hacks and devices on; spawns off. */
	public static final Permissions DEFAULT = new Permissions(Payloads.PERM_EXPLOSIONS | Payloads.PERM_DAMAGE | Payloads.PERM_HACKS | Payloads.PERM_DEVICES,
		ALL_DEVICES);

	public Permissions {
		bits &= ALL;
		devices &= ALL_DEVICES;
	}

	public boolean has(final Toggle toggle) {
		return (this.bits & toggle.bit()) != 0;
	}

	/** Phase 4: one device type bit (section 1.11), on top of the devices toggle. */
	public boolean device(final int typeBit) {
		return this.has(Toggle.DEVICES) && (this.devices & typeBit) != 0;
	}

	public boolean device(final DeviceType type) {
		return this.device(type.bit());
	}

	public Permissions with(final Toggle toggle, final boolean on) {
		return new Permissions(on ? this.bits | toggle.bit() : this.bits & ~toggle.bit(), this.devices);
	}

	/** One device type on or off (the devices toggle itself is unchanged). */
	public Permissions with(final DeviceType type, final boolean on) {
		return new Permissions(this.bits, on ? this.devices | type.bit() : this.devices & ~type.bit());
	}

	private static volatile HostsData store = new HostsData();

	/** The world's store (server start), or a fresh in-memory one (tests, server stop). */
	static void use(final HostsData data) {
		store = data;
	}

	static HostsData store() {
		return store;
	}

	/** What guests may do to this host's game (the defaults when it never changed them). Server thread. */
	public static Permissions of(final UUID host) {
		return store.perms(host);
	}

	/** Server thread: the host's new toggles (saved with the world). */
	static void set(final UUID host, final Permissions perms) {
		store.setPerms(host, perms);
	}

	/**
	 * May this actor's action reach the owner's Cyberpunk game? True for the owner itself, for no actor (the world: a creeper, a
	 * dispenser), and for another host; for a guest, only when the owner's toggle is on. Server thread.
	 */
	public static boolean allows(final UUID owner, final @Nullable UUID actor, final Toggle toggle) {
		if (actor == null || actor.equals(owner) || HostSessions.isHost(actor)) {
			return true;
		}

		return of(owner).has(toggle);
	}

	/**
	 * May this actor use the owner's device of this type (Phase 4)? As {@link #allows}, and for a guest both the owner's devices
	 * toggle and the type's bit must be on. Server thread.
	 */
	public static boolean allowsDevice(final UUID owner, final @Nullable UUID actor, final DeviceType type) {
		if (actor == null || actor.equals(owner) || HostSessions.isHost(actor)) {
			return true;
		}

		return of(owner).device(type);
	}

	/** Server thread: a host turns one device type on or off for its guests (saved; its client hears it in a fresh hs_ack). */
	public static void setDevice(final ServerPlayer host, final DeviceType type, final boolean on) {
		ServerNet.setPerms(host, of(host.getUUID()).with(type, on));
	}
}
