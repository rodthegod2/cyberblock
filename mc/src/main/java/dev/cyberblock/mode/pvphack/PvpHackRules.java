package dev.cyberblock.mode.pvphack;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.hack.breach.BreachContent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The pure rules of hacking players (megaplan 8-X1): who may be hacked (opt-in and war zones), the refusals in the order the player
 * hears them, the player cooldowns, each chip's effect (shorter against the {@code netrunner} implant) and how the counterplay ends an
 * intrusion. Times are server ticks at 20 TPS.
 */
public final class PvpHackRules {
	/** The six chips that work on players; the rest (Contagion, Synapse Burnout, Cyberpsychosis, the Redstone Lever) stay NPC-only. */
	public static final Set<Hack> CHIPS = Collections.unmodifiableSet(EnumSet.of(Hack.SHORT_CIRCUIT, Hack.REBOOT_OPTICS, Hack.CRIPPLE_MOVEMENT,
		Hack.PING, Hack.OVERHEAT, Hack.MEMORY_WIPE));
	/** The netrunner implant never shortens an effect below this (half a second). */
	public static final int MIN_TICKS = 10;
	/** The target's few seconds to start the short Breach before its own 5 s timer runs. */
	public static final int START_TICKS = 60;
	/** Slack after the short Breach's timer before the intrusion lands regardless (the client's last pick on its way). */
	public static final int GRACE_TICKS = 10;
	/** The upload when the target cannot breach (already in a breach, or the screen could not open): the warning, then the hit. */
	public static final int UPLOAD_TICKS = 30;

	/** Why a player hack is refused; NONE: it goes. */
	public enum Refusal {
		NONE,
		SELF,
		NOT_A_PLAYER_CHIP,
		NO_DECK,
		/** The hacker himself has PvP off and stands in no war zone he counts in. */
		YOU_OUT,
		/** Locked by the join password, a spectator, or dead. */
		TARGET_SHIELDED,
		/** The target did not opt in (and is no linked player inside a war zone). */
		TARGET_OUT,
		/** Another hack is uploading into the target now. */
		BUSY,
		NO_RAM
	}

	/** How an intrusion ended. */
	public enum Result {
		FIREWALL,
		BREACH,
		LANDED
	}

	/**
	 * A player in a hack: PvP on (8M's toggle), inside a war zone (8M's Gang Wars), Cyberpunk-linked, and shielded (join-locked,
	 * spectating or dead).
	 */
	public record Party(boolean pvpOn, boolean warZone, boolean linked, boolean shielded) {
	}

	/** One landed chip: how long it lasts (stun, blindness, slowness, outline, burn, or the dropped item's pick-up delay), its damage and the effect level. */
	public record Effect(int ticks, float damage, int amplifier) {
	}

	private PvpHackRules() {
	}

	/** The opt-in rule: PvP on, or a Cyberpunk-linked player inside a war zone. A Minecraft-only guest who has not opted in never. */
	public static boolean targetable(final boolean pvpOn, final boolean warZone, final boolean linked) {
		return pvpOn || warZone && linked;
	}

	/**
	 * Whether this hack may start, in the order the hacker hears the reasons. The hacker is held to the target's rule: nobody hacks into a
	 * fight he cannot be hacked back in.
	 *
	 * @param self the hacker aims at himself
	 * @param cost what the chip costs him now (Ram.cost)
	 * @param busy another hack is uploading into the target
	 */
	public static Refusal check(final Hack hack, final boolean self, final Party hacker, final Party target, final boolean hasDeck, final int ram,
		final int cost, final boolean busy) {
		if (self) {
			return Refusal.SELF;
		}

		if (!CHIPS.contains(hack)) {
			return Refusal.NOT_A_PLAYER_CHIP;
		}

		if (!hasDeck) {
			return Refusal.NO_DECK;
		}

		if (!targetable(hacker.pvpOn(), hacker.warZone(), hacker.linked())) {
			return Refusal.YOU_OUT;
		}

		if (target.shielded()) {
			return Refusal.TARGET_SHIELDED;
		}

		if (!targetable(target.pvpOn(), target.warZone(), target.linked())) {
			return Refusal.TARGET_OUT;
		}

		if (busy) {
			return Refusal.BUSY;
		}

		return ram >= cost ? Refusal.NONE : Refusal.NO_RAM;
	}

	/** How long the chip recharges after a player hack; longer than on an NPC, so a fight is not a stun lock. Other chips: their own. */
	public static int cooldownTicks(final Hack hack) {
		return switch (hack) {
			case SHORT_CIRCUIT, CRIPPLE_MOVEMENT -> 200;
			case REBOOT_OPTICS -> 300;
			case PING -> 100;
			case OVERHEAT -> 240;
			case MEMORY_WIPE -> 400;
			default -> hack.cooldownTicks();
		};
	}

	/**
	 * What a landed chip does: Short Circuit stuns (Slowness VII) 1.5 s and hits 4; Reboot Optics blinds 5 s; Cripple Movement slows
	 * (Slowness III) 6 s; Ping outlines 10 s; Overheat burns 5 s and hits 2; Memory Wipe's dropped item waits 3 s before anyone picks it
	 * up. The netrunner implant halves every time (at least {@link #MIN_TICKS}); damage stays.
	 */
	public static Effect effect(final Hack hack, final boolean netrunner) {
		Effect full = switch (hack) {
			case SHORT_CIRCUIT -> new Effect(30, 4.0F, 6);
			case REBOOT_OPTICS -> new Effect(100, 0.0F, 0);
			case CRIPPLE_MOVEMENT -> new Effect(120, 0.0F, 2);
			case PING -> new Effect(200, 0.0F, 0);
			case OVERHEAT -> new Effect(100, 2.0F, 0);
			case MEMORY_WIPE -> new Effect(60, 0.0F, 0);
			default -> new Effect(MIN_TICKS, 0.0F, 0);
		};
		return netrunner ? new Effect(Math.max(MIN_TICKS, full.ticks() / 2), full.damage(), full.amplifier()) : full;
	}

	/** The {@code glitch} DOWN's whole seconds (megaplan section 1.9: 0..10), rounded up. */
	public static int glitchSeconds(final int ticks) {
		return Math.clamp((Math.max(0, ticks) + 19) / 20, 0, 10);
	}

	/** A charged Firewall stops the hack before any upload; a solved Breach stops it; a failed, timed-out, aborted or missing Breach lets it land. */
	public static Result result(final boolean firewall, final boolean breachSolved) {
		return firewall ? Result.FIREWALL : breachSolved ? Result.BREACH : Result.LANDED;
	}

	/** Ticks from the warning until the intrusion lands at the latest: a few seconds to start the short Breach plus its timer, or the bare upload. */
	public static int uploadTicks(final boolean breach) {
		return breach ? START_TICKS + BreachContent.SHORT.seconds() * 20 + GRACE_TICKS : UPLOAD_TICKS;
	}
}
