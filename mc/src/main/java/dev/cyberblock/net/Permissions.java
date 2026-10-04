package dev.cyberblock.net;

import java.util.Locale;
import java.util.UUID;
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

	public Permissions with(final Toggle toggle, final boolean on) {
		return new Permissions(on ? this.bits | toggle.bit() : this.bits & ~toggle.bit(), this.devices);
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
}
