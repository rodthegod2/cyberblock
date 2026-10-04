package dev.cyberblock.hack.actions;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Phase 0 check (h) at runtime (megaplan section 4.1 h, docs/notes/phase0-feasibility.md): the desk verdict is that Cyberpunk
 * runs a remote quickhack or device action on every target its game has streamed, whatever V's distance and line of sight, but
 * the in-game check has not run yet. So remote actions (a guest's quickhack, the interaction map's `rhack`) go to any streamed
 * target, and the written fallback switches itself on the first time Cyberpunk refuses a target beyond 50 m for range or sight:
 * from then on (until the server restarts) only targets within 50 m of V are sent, the rest answer
 * {@code cyberblock.hack.out_of_range}. One log line says which path is active (at the first remote action and on the switch).
 * Streamed means: a live figure, a device in the owner's list, or a point or district within {@link #STREAMED_RANGE} of V.
 */
public final class RangeRule {
	/** The written fallback: remote actions only this close to V. */
	public static final double FALLBACK_RANGE = 50.0;
	/** A point hack's centre counts as streamed this close to V (Night City's crowd and traffic streaming; the blackout fallback's 150 m). */
	public static final double STREAMED_RANGE = 150.0;
	/** The server's rule. */
	public static final RangeRule SERVER = new RangeRule();
	/** A refusal reason that means range or sight (the scripts' own `target gone` means not streamed, which is not a range limit). */
	private static final Pattern RANGE_REASON = Pattern.compile("range|sight|too far|distance|not visible|unreachable|obstructed",
		Pattern.CASE_INSENSITIVE);

	private boolean fallback;
	private String why = "";
	private boolean announced;

	/** The 50 m fallback is active. */
	public boolean fallback() {
		return this.fallback;
	}

	/** May an action at a target `distance` metres from V go? It must be streamed; with the fallback on, also within 50 m. */
	public boolean allows(final double distance, final boolean streamed) {
		return streamed && (!this.fallback || distance <= FALLBACK_RANGE);
	}

	/**
	 * Cyberpunk answered a remote action at a target `distance` metres from V. A refusal for range or sight beyond 50 m switches the
	 * fallback on (the in-game evidence the desk check could not give). Returns true when this answer switched it.
	 */
	public boolean observe(final boolean ok, final String reason, final double distance, final String what) {
		if (ok || this.fallback || distance <= FALLBACK_RANGE || !RANGE_REASON.matcher(reason).find()) {
			return false;
		}

		this.fallback = true;
		this.why = String.format(Locale.ROOT, "Cyberpunk refused %s at %.0f m: %s", what, distance, reason);
		this.announced = false;
		return true;
	}

	/** The log line once per path (the first remote action, then each switch); null when it was said already. */
	public @Nullable String takeLine() {
		if (this.announced) {
			return null;
		}

		this.announced = true;
		return this.line();
	}

	/** The line saying which path is active. */
	public String line() {
		return this.fallback
			? "hack range: fallback active: remote hacks only within 50 m of V (" + this.why + ")"
			: "hack range: every streamed target (Phase 0 h desk verdict, unconfirmed in game; the 50 m fallback switches on if Cyberpunk refuses a far target for range)";
	}

	/** Server stop: the next server checks again. */
	public void reset() {
		this.fallback = false;
		this.why = "";
		this.announced = false;
	}
}
