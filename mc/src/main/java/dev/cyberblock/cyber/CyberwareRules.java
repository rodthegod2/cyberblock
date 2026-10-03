package dev.cyberblock.cyber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The pure rules of the cyberware (plan retest2 section 1.7): slots, the Y selection, perk-adjusted times, triggers. */
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
	/** The activate key (Y): released within this many client ticks it fires, held this long it cycles the selection. */
	public static final int TAP_TICKS = 8;
	/** Projectile launch system: hitscan reach, and the closest it fires. */
	public static final double PLS_RANGE = 64.0;
	public static final double PLS_MIN = 4.0;

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
	 * The one implant in the Operating System slot (the cyberdeck, Sandevistan or Berserk), or null when it is free. While it
	 * holds anything but the deck, a carried deck does not count (Ram.hasDeck).
	 */
	public static @Nullable Cyberware osImplant(final Map<String, String> slots) {
		return Cyberware.byId(slots.get(CyberSlot.OPERATING_SYSTEM.key(0)));
	}

	/**
	 * Installs into the first free position of the implant's slot (one holding an unknown id counts as free), else replaces
	 * position 0. An implant already installed anywhere changes nothing.
	 */
	public static Installed install(final Map<String, String> slots, final Cyberware cyberware) {
		for (Map.Entry<String, String> e : slots.entrySet()) {
			if (e.getValue().equals(cyberware.id())) {
				return new Installed(slots, Install.ALREADY, null, e.getKey());
			}
		}

		Map<String, String> next = new HashMap<>(slots);
		CyberSlot slot = cyberware.slot();
		for (int n = 0; n < slot.capacity(); n++) {
			String key = slot.key(n);
			if (Cyberware.byId(slots.get(key)) == null) {
				next.put(key, cyberware.id());
				return new Installed(Map.copyOf(next), Install.INSTALLED, null, key);
			}
		}

		String first = slot.key(0);
		Cyberware replaced = Cyberware.byId(next.put(first, cyberware.id()));
		return new Installed(Map.copyOf(next), Install.REPLACED, replaced, first);
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

	private static List<Cyberware> selectable(final Collection<Cyberware> installed) {
		return activeOrder(installed).stream().filter(c -> c.kind() == Cyberware.Kind.ACTIVE).toList();
	}

	/** What Y fires: the selected implant while it is installed and ACTIVE, else the first ACTIVE one; null without any. */
	public static @Nullable Cyberware defaultSelected(final Collection<Cyberware> installed, final @Nullable Cyberware selected) {
		List<Cyberware> list = selectable(installed);
		if (selected != null && list.contains(selected)) {
			return selected;
		}

		return list.isEmpty() ? null : list.getFirst();
	}

	/** The ACTIVE implant after `current` (wrapping round), the first one when `current` is none of them; null without any. */
	public static @Nullable Cyberware next(final Collection<Cyberware> installed, final @Nullable Cyberware current) {
		List<Cyberware> list = selectable(installed);
		if (list.isEmpty()) {
			return null;
		}

		int at = list.indexOf(current);
		return at < 0 ? list.getFirst() : list.get((at + 1) % list.size());
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
