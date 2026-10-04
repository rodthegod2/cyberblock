package dev.cyberblock.hack.redstone;

import dev.cyberblock.device.Device;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the redstone hacking blocks (megaplan 6-R1), pure: what a Device Relay asks Night City for, why an action is refused
 * (link, owner's toggles, quest locks, the Phase 0 h reach), the sensors' signal strengths and the Cyberdeck block's RAM.
 */
public final class RedstoneRules {
	/** Proximity Sensor target bits (its screen): people, cars, V (a host's player). */
	public static final int PERSON = 1, CAR = 2, V = 4, ALL_TARGETS = 7;
	/** Sensor and Breach Port range, blocks. */
	public static final int MIN_RANGE = 1, MAX_RANGE = 16, DEFAULT_RANGE = 8;
	/** A Breach Port hacks at most this many targets per signal (nearest first, while the Cyberdeck block's RAM lasts). */
	public static final int MAX_TARGETS = 3;
	/** The Cyberdeck block: RAM it holds, and one point back every this many ticks (5 s). */
	public static final int DECK_MAX_RAM = 16;
	public static final long DECK_REFILL_TICKS = 100;
	/** A Charge's blast in Night City: the radius a TNT block would have. */
	public static final double CHARGE_POWER = 4.0;
	/** Hosts whose V is this close hear a Charge (as ExplosionEvents.REACH). */
	public static final double CHARGE_REACH = 128.0;

	/** Why an action did not reach Night City: the lang key and its English fallback. */
	public enum Refusal {
		NONE("", ""),
		NO_LINK("cyberblock.redstone.no_link", "Not linked: pick a device with the Cyberdeck Link, then right-click this block with it"),
		UNRESOLVED("cyberblock.redstone.unresolved", "Linked device unresolved: its host is offline or the device is not in its list"),
		BLOCKED("cyberblock.guest.blocked", "That host does not let guests do this to their Night City"),
		QUEST_LOCKED("cyberblock.device.quest_locked", "Locked by Night City (a quest or a scene): it cannot be used now"),
		OUT_OF_RANGE("cyberblock.hack.out_of_range", "Out of range: Night City reaches only targets within 50 m of V"),
		FROZEN("cyberblock.redstone.loop", "Redstone loop: this device is frozen for 30 s"),
		NO_DECK("cyberblock.redstone.no_deck", "The Breach Port needs a Cyberdeck block next to it"),
		NO_RAM("cyberblock.redstone.no_ram", "The Cyberdeck block is out of RAM"),
		NO_TARGET("cyberblock.redstone.no_target", "No target in range");

		private final String key;
		private final String fallback;

		Refusal(final String key, final String fallback) {
			this.key = key;
			this.fallback = fallback;
		}

		public String key() {
			return this.key;
		}

		public String fallback() {
			return this.fallback;
		}
	}

	private RedstoneRules() {
	}

	/**
	 * What a Device Relay's rising edge asks for: a door opens or closes (a locked one is unlocked and opened: section 1.11 lets a
	 * Device Relay open what needs Breach), every other device toggles.
	 */
	public static List<String> relayActs(final Device d) {
		if (d.type() != DeviceType.DOOR) {
			return List.of("toggle");
		}

		if (d.open()) {
			return List.of("close");
		}

		return d.locked() ? List.of("unlock", "open") : List.of("open");
	}

	/**
	 * May the actor reach the owner's game with this action? {@code privileged}: the actor is the owner, another host, or nobody (the
	 * world), as Permissions.allows has it; a guest needs the owner's toggle (for a device, the devices toggle and its type bit).
	 */
	public static boolean allowed(final Permissions owner, final boolean privileged, final Permissions.Toggle toggle) {
		return privileged || owner.has(toggle);
	}

	public static boolean allowedDevice(final Permissions owner, final boolean privileged, final DeviceType type) {
		return privileged || owner.device(type);
	}

	/**
	 * A Device Relay's checks in order: linked, resolved (owner online and the device in its list), the loop freeze, the owner's
	 * toggles, a quest lock, then the Phase 0 h reach.
	 */
	public static Refusal relay(final boolean linked, final @Nullable Device d, final boolean frozen, final boolean allowed, final boolean reaches) {
		if (!linked) {
			return Refusal.NO_LINK;
		}

		if (d == null) {
			return Refusal.UNRESOLVED;
		}

		if (frozen) {
			return Refusal.FROZEN;
		}

		if (!allowed) {
			return Refusal.BLOCKED;
		}

		if (d.questLocked()) {
			return Refusal.QUEST_LOCKED;
		}

		return reaches ? Refusal.NONE : Refusal.OUT_OF_RANGE;
	}

	/** One Breach Port target's checks: the owner's hacks toggle, then the Phase 0 h reach. */
	public static Refusal hack(final boolean allowed, final boolean reaches) {
		return !allowed ? Refusal.BLOCKED : reaches ? Refusal.NONE : Refusal.OUT_OF_RANGE;
	}

	/** Heat Sensor: the nearest host's NCPD heat (stats.wanted 0..5) times 3. */
	public static int heatSignal(final int wanted) {
		return Math.clamp(wanted, 0, 5) * 3;
	}

	/** Proximity Sensor: full signal when anything it watches is in range. */
	public static int proximitySignal(final int mask, final int people, final int cars, final int vs) {
		boolean seen = (mask & PERSON) != 0 && people > 0 || (mask & CAR) != 0 && cars > 0 || (mask & V) != 0 && vs > 0;
		return seen ? 15 : 0;
	}

	public static int range(final int r) {
		return Math.clamp(r, MIN_RANGE, MAX_RANGE);
	}

	/**
	 * The Cyberdeck block's RAM: {@code stored} at tick {@code since}, one point back every {@link #DECK_REFILL_TICKS}, at most
	 * {@link #DECK_MAX_RAM}.
	 */
	public record Deck(int stored, long since) {
		public static Deck full(final long now) {
			return new Deck(DECK_MAX_RAM, now);
		}

		public int ram(final long now) {
			long refilled = Math.max(0L, now - this.since) / DECK_REFILL_TICKS;
			return (int) Math.min(DECK_MAX_RAM, this.stored + refilled);
		}

		/** After paying cost at tick now (the refill already under way keeps its progress), or null when the RAM is short. */
		public @Nullable Deck take(final int cost, final long now) {
			int ram = this.ram(now);
			if (cost > ram) {
				return null;
			}

			return new Deck(ram - cost, this.restart(ram, now));
		}

		/** A refund (a hack Night City refused), never past the maximum. */
		public Deck give(final int amount, final long now) {
			int ram = this.ram(now);
			return new Deck(Math.min(DECK_MAX_RAM, ram + amount), this.restart(ram, now));
		}

		/** Comparator output 0..15. */
		public int signal(final long now) {
			return this.ram(now) * 15 / DECK_MAX_RAM;
		}

		private long restart(final int ram, final long now) {
			if (ram >= DECK_MAX_RAM) {
				return now;
			}

			long elapsed = Math.max(0L, now - this.since);
			return this.since + elapsed / DECK_REFILL_TICKS * DECK_REFILL_TICKS;
		}
	}
}
