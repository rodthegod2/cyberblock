package dev.cyberblock.cyber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The pure rules of the cyberware (plan retest2 section 1.7, retest3 section 1.8): slots, the three fire keys, perk-adjusted times. */
public final class CyberwareRules {
	/** The server's tick rate while a Sandevistan or Kerenzikov slows time, and the normal one. */
	public static final float SLOW_TICK_RATE = 10.0F;
	public static final float NORMAL_TICK_RATE = 20.0F;
	/** The Sandevistan perk upgrades the implant to 8 s active and 18 s cooldown. */
	static final int SANDEVISTAN_PERK_DURATION = 160;
	static final int SANDEVISTAN_PERK_COOLDOWN = 360;
	/** Biomonitor: fires at or below this share of max health. */
	static final float BIOMONITOR_THRESHOLD = 0.3F;
	/** Fortified ankles: the jump key held this long on the ground, and V off the ground at most this long after the release. */
	public static final int CHARGE_HOLD_TICKS = 10;
	public static final int CHARGE_TAKEOFF_TICKS = 4;
	/** Projectile launch system: hitscan reach, and the closest it fires. */
	public static final double PLS_RANGE = 64.0;
	public static final double PLS_MIN = 4.0;
	/** Mantis lunge (plan retest3 section 1.8): reach, the hit on arrival, arrival distance, and the flight's ticks. */
	public static final double LUNGE_RANGE = 10.0;
	public static final float LUNGE_HIT = 1.5F;
	public static final double LUNGE_ARRIVE = 2.5;
	public static final int LUNGE_TICKS = 12;
	/** Gorilla slam: radius, knockback strength and the small hit that carries it. */
	public static final double SLAM_RADIUS = 4.0;
	public static final double SLAM_KNOCKBACK = 1.0;
	public static final float SLAM_DAMAGE = 2.0F;
	/** Monowire lash: reach, the half angle of its 120 degree arc, damage, and Slowness II for 60 ticks. */
	public static final double LASH_RANGE = 5.0;
	public static final double LASH_HALF_ANGLE = 60.0;
	public static final float LASH_DAMAGE = 4.0F;
	public static final int LASH_SLOW_TICKS = 60;

	/**
	 * The three fire keys (plan retest3 section 1.7): the Operating System implant (Y), the Arms implant (L) and the Optical Camo
	 * (O). Each fires the ACTIVE implant of its own place, so no implant waits behind another one's key.
	 */
	public enum Trigger {
		OS("key.cyberblock.activate"),
		ARMS("key.cyberblock.arms"),
		CAMO("key.cyberblock.camo");

		private final String keyName;

		Trigger(final String keyName) {
			this.keyName = keyName;
		}

		/** The key mapping's name, for Component.keybind. */
		public String keyName() {
			return this.keyName;
		}

		/** "os", "arms", "camo" (logs and lang keys). */
		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		/** The key that fires this implant, or null when it is not ACTIVE (passive and automatic implants have none). */
		public static @Nullable Trigger of(final Cyberware cyberware) {
			if (cyberware.kind() != Cyberware.Kind.ACTIVE) {
				return null;
			}

			if (cyberware == Cyberware.OPTICAL_CAMO) {
				return CAMO;
			}

			return switch (cyberware.slot()) {
				case OPERATING_SYSTEM -> OS;
				case ARMS -> ARMS;
				default -> null;
			};
		}
	}

	/** What an install did. */
	public enum Install {
		INSTALLED,
		/** The slot was full: its first implant went out (returned to the player). */
		REPLACED,
		/** Already installed: nothing changed. */
		ALREADY
	}

	/**
	 * @param slots the new slot map (slot key to id)
	 * @param replaced the implant taken out, or null
	 * @param slotKey where it went (or already is)
	 */
	public record Installed(Map<String, String> slots, Install outcome, @Nullable Cyberware replaced, String slotKey) {
	}

	/**
	 * When an ACTIVE or AUTO implant fired (`start`), until when its effect lasts and when it may fire again, in level game time
	 * (ticks). Saved per implant (CyberData.CYBER_READY) and synced, so the HUD draws the bars from it.
	 */
	public record Timer(long start, long activeUntil, long readyAt) {
		public static final Timer NONE = new Timer(0L, 0L, 0L);

		/** Fired now: active for `activeTicks` game ticks, then `cooldownTicks` cooling down. */
		public static Timer fire(final long now, final int activeTicks, final int cooldownTicks) {
			return new Timer(now, now + activeTicks, now + activeTicks + cooldownTicks);
		}

		public boolean active(final long now) {
			return now >= this.start && now < this.activeUntil;
		}

		public boolean ready(final long now) {
			return now >= this.readyAt;
		}

		/** 1 when it just fired, falling to 0 when the effect ends; 0 when not active. */
		public float activeFraction(final long now) {
			return this.active(now) ? (float) (this.activeUntil - now) / (this.activeUntil - this.start) : 0.0F;
		}

		/** The share of the cooldown still ahead: 1 while active and when it just started, 0 when ready. */
		public float remainingFraction(final long now) {
			long cooling = this.readyAt - this.activeUntil;
			if (this.ready(now) || cooling <= 0L) {
				return 0.0F;
			}

			return Math.min(1.0F, (float) (this.readyAt - now) / cooling);
		}

		/** Whole seconds (at 20 TPS) until it may fire again, rounded up; 0 when ready. */
		public long secondsLeft(final long now) {
			return this.ready(now) ? 0L : (this.readyAt - now + 19L) / 20L;
		}

		/** The effect is ended early now (camo broken): the cooldown starts now, as when the effect runs out. */
		public Timer stop(final long now) {
			return this.active(now) ? new Timer(this.start, now, now + this.readyAt - this.activeUntil) : this;
		}
	}

	private CyberwareRules() {
	}

	/** The implants a slot map holds; ids nobody knows (an older or edited save) are left out. */
	public static Set<Cyberware> installed(final Map<String, String> slots) {
		Set<Cyberware> set = EnumSet.noneOf(Cyberware.class);
		for (String id : slots.values()) {
			Cyberware c = Cyberware.byId(id);
			if (c != null) {
				set.add(c);
			}
		}

		return set;
	}

	/**
	 * Installs into the implant's fixed position (CyberSlot.position: deck 0, Sandevistan/Berserk 1 of the Operating System
	 * slot), replacing whatever is there; any other implant goes into the first free position of its slot (one holding an
	 * unknown id counts as free), else replaces position 0. An implant already installed anywhere changes nothing.
	 */
	public static Installed install(final Map<String, String> slots, final Cyberware cyberware) {
		for (Map.Entry<String, String> e : slots.entrySet()) {
			if (e.getValue().equals(cyberware.id())) {
				return new Installed(slots, Install.ALREADY, null, e.getKey());
			}
		}

		Map<String, String> next = new HashMap<>(slots);
		CyberSlot slot = cyberware.slot();
		int fixed = CyberSlot.position(cyberware);
		if (fixed < 0) {
			for (int n = 0; n < slot.capacity(); n++) {
				String key = slot.key(n);
				if (Cyberware.byId(slots.get(key)) == null) {
					next.put(key, cyberware.id());
					return new Installed(Map.copyOf(next), Install.INSTALLED, null, key);
				}
			}
		}

		String key = slot.key(Math.max(0, fixed));
		Cyberware replaced = Cyberware.byId(next.put(key, cyberware.id()));
		return new Installed(Map.copyOf(next), replaced == null ? Install.INSTALLED : Install.REPLACED, replaced, key);
	}

	/**
	 * An older save (one Operating System position) kept its Sandevistan or Berserk in operating_system/0: every implant with a
	 * fixed position moves there (swapping with what holds it), once on join before the first `cyberware` send. Returns the
	 * same map when nothing moved.
	 */
	public static Map<String, String> normalize(final Map<String, String> slots) {
		Map<String, String> next = new HashMap<>(slots);
		boolean moved = false;
		for (CyberSlot slot : CyberSlot.values()) {
			for (int n = 0; n < slot.capacity(); n++) {
				String key = slot.key(n);
				Cyberware c = Cyberware.byId(next.get(key));
				int fixed = c == null ? -1 : CyberSlot.position(c);
				if (fixed >= 0 && fixed != n) {
					String to = slot.key(fixed);
					String there = next.get(to);
					next.put(to, c.id());
					if (there == null) {
						next.remove(key);
					} else {
						next.put(key, there);
					}

					moved = true;
				}
			}
		}

		return moved ? Map.copyOf(next) : slots;
	}

	/** The HUD's row: every installed ACTIVE or AUTO implant, in catalogue order. */
	public static List<Cyberware> activeOrder(final Collection<Cyberware> installed) {
		List<Cyberware> order = new ArrayList<>();
		for (Cyberware c : Cyberware.values()) {
			if (c.kind() != Cyberware.Kind.PASSIVE && installed.contains(c)) {
				order.add(c);
			}
		}

		return order;
	}

	/** What a fire key fires: the installed ACTIVE implant of its place (Trigger.of), the first in catalogue order; null without one. */
	public static @Nullable Cyberware forTrigger(final Collection<Cyberware> installed, final Trigger trigger) {
		for (Cyberware c : Cyberware.values()) {
			if (installed.contains(c) && Trigger.of(c) == trigger) {
				return c;
			}
		}

		return null;
	}

	/**
	 * Mantis lunge: Steve's launch towards a target `delta` blocks away (x, y, z): the horizontal part covers it in about six
	 * ticks (at most 2 blocks a tick), the vertical part lifts him a little (clamped to -0.5..1.0).
	 */
	public static double[] lungeVelocity(final double dx, final double dy, final double dz) {
		double h = Math.hypot(dx, dz);
		double speed = Math.min(2.0, h / 6.0);
		double vx = h > 1.0E-6 ? dx / h * speed : 0.0;
		double vz = h > 1.0E-6 ? dz / h * speed : 0.0;
		return new double[]{vx, Math.clamp(dy / 6.0 + 0.2, -0.5, 1.0), vz};
	}

	/** Monowire lash: `to` (target minus eye) is within LASH_RANGE and within LASH_HALF_ANGLE of the look direction, horizontally. */
	public static boolean inLash(final double lookX, final double lookZ, final double toX, final double toY, final double toZ) {
		double dist = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
		double look = Math.hypot(lookX, lookZ);
		double to = Math.hypot(toX, toZ);
		if (dist > LASH_RANGE) {
			return false;
		}

		if (to < 0.5 || look < 1.0E-6) {
			return true; // right at his feet, or looking straight up or down: the whip reaches all round
		}

		double cos = (lookX * toX + lookZ * toZ) / (look * to);
		return cos >= Math.cos(Math.toRadians(LASH_HALF_ANGLE)) - 1.0E-9;
	}

	/** The duration with the perks (the Sandevistan perk upgrades the Sandevistan implant). */
	public static int durationTicks(final Cyberware cyberware, final boolean sandevistanPerk) {
		return cyberware == Cyberware.SANDEVISTAN && sandevistanPerk ? SANDEVISTAN_PERK_DURATION : cyberware.durationTicks();
	}

	public static int cooldownTicks(final Cyberware cyberware, final boolean sandevistanPerk) {
		return cyberware == Cyberware.SANDEVISTAN && sandevistanPerk ? SANDEVISTAN_PERK_COOLDOWN : cyberware.cooldownTicks();
	}

	/**
	 * Game ticks an effect lasts: its duration at 20 TPS, or, for one that slows the server to SLOW_TICK_RATE, as many ticks as
	 * the slow rate runs in the same wall time (a 6 s Sandevistan is 60 ticks at 10 TPS).
	 */
	public static int gameTicks(final int durationTicks, final boolean slowsTime) {
		return slowsTime ? Math.round(durationTicks * SLOW_TICK_RATE / NORMAL_TICK_RATE) : durationTicks;
	}

	/**
	 * The once-per-player migration (section 1.7): the perks that used to work alone now upgrade an implant, so their owners get
	 * that implant's item (never installed for them): double_jump gives reinforced tendons, sandevistan and optical_camo their own.
	 */
	public static List<Cyberware> migration(final Predicate<Perk> owns) {
		List<Cyberware> items = new ArrayList<>();
		if (owns.test(Perk.DOUBLE_JUMP)) {
			items.add(Cyberware.REINFORCED_TENDONS);
		}

		if (owns.test(Perk.SANDEVISTAN)) {
			items.add(Cyberware.SANDEVISTAN);
		}

		if (owns.test(Perk.OPTICAL_CAMO)) {
			items.add(Cyberware.OPTICAL_CAMO);
		}

		return items;
	}

	/** Biomonitor: alive and at or below 30 % of max health. */
	public static boolean biomonitor(final float health, final float maxHealth) {
		return health > 0.0F && health <= maxHealth * BIOMONITOR_THRESHOLD + 1.0E-4F;
	}

	/** Fortified ankles: the jump key was held at least 10 ticks, and V left the ground at most 4 ticks after it was let go. */
	public static boolean chargeJump(final int heldTicks, final int sinceReleaseTicks) {
		return heldTicks >= CHARGE_HOLD_TICKS && sinceReleaseTicks >= 0 && sinceReleaseTicks <= CHARGE_TAKEOFF_TICKS;
	}
}
