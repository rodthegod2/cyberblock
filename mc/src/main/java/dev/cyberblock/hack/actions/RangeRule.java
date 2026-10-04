package dev.cyberblock.hack.actions;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Phase 0 check (h) at runtime (megaplan section 4.1 h, docs/notes/phase0-feasibility.md): the desk verdict is that Cyberpunk
 * runs a remote quickhack or device action on every target its game has streamed, whatever V's distance and line of sight, but
 * the in-game check has not run yet. So remote actions (a guest's quickhack, the interaction map's `rhack`, the redstone blocks'
 * Device Relay and Breach Port) go to any streamed target, and the written fallback switches itself on the first time Cyberpunk
 * refuses a target beyond 50 m for range or sight: from then on (until the server restarts) only targets within 50 m of V are
 * sent, the rest answer {@code cyberblock.hack.out_of_range}. {@code -Dcyberblock.remoteRange=near} forces the fallback from the
 * start, {@code =streamed} keeps it off. One log line says which path is active (at the first remote action and on the switch).
 * Streamed means: a live figure, a device in the owner's list, or a point or district within {@link #STREAMED_RANGE} of V.
 */
public final class RangeRule {
	/** The written fallback: remote actions only this close to V. */
	public static final double FALLBACK_RANGE = 50.0;
	/** A point hack's centre counts as streamed this close to V (Night City's crowd and traffic streaming; the blackout fallback's 150 m). */
	public static final double STREAMED_RANGE = 150.0;
	/** The JVM property that forces a path ("near" or "streamed"). */
	public static final String PROPERTY = "cyberblock.remoteRange";
	/** The server's rule. */
	public static final RangeRule SERVER = new RangeRule(System.getProperty(PROPERTY));
	/** A refusal reason that means range or sight (whole words). */
	private static final Pattern RANGE_REASON = Pattern.compile("\\b(range|sight|too far|distance|not visible|unreachable|obstructed)\\b",
		Pattern.CASE_INSENSITIVE);
	/**
	 * The scripts' own answers for a target they do not have now, never a game range check: `target gone` (not streamed) and
	 * Devices.reds' `not in range` (the device is not in its last list).
	 */
	private static final Set<String> NOT_STREAMED = Set.of("target gone", "not in range");

	private final @Nullable String forced;
	private boolean fallback;
	private String why = "";
	private boolean announced;

	/** The default: the desk verdict until Cyberpunk says otherwise. */
	public RangeRule() {
		this(null);
	}

	/** With the JVM property's value: "near" forces the fallback, "streamed" keeps it off, anything else is the default. */
	RangeRule(final @Nullable String property) {
		String p = property == null ? "" : property.trim().toLowerCase(Locale.ROOT);
		this.forced = p.equals("near") || p.equals("streamed") ? p : null;
		this.reset();
	}

	/** The 50 m fallback is active. */
	public boolean fallback() {
		return this.fallback;
	}

	/** May an action at a target `distance` metres from V go? It must be streamed; with the fallback on, also within 50 m. */
	public boolean allows(final double distance, final boolean streamed) {
		return streamed && (!this.fallback || distance <= FALLBACK_RANGE);
	}

	/** A refusal reason that names a range or sight check, not merely a target the scripts do not have now. */
	public static boolean rangeRefusal(final String reason) {
		return !NOT_STREAMED.contains(reason.trim().toLowerCase(Locale.ROOT)) && RANGE_REASON.matcher(reason).find();
	}

	/**
	 * Cyberpunk answered a remote action at a target `distance` metres from V (NaN or negative: unknown). A refusal for range or
	 * sight beyond 50 m switches the fallback on (the in-game evidence the desk check could not give). Returns true when this
	 * answer switched it.
	 */
	public boolean observe(final boolean ok, final String reason, final double distance, final String what) {
		if (ok || this.fallback || "streamed".equals(this.forced) || !(distance > FALLBACK_RANGE) || !rangeRefusal(reason)) {
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
		if (this.fallback) {
			return "hack range: fallback active: remote hacks only within 50 m of V (" + this.why + ")";
		}

		return "streamed".equals(this.forced)
			? "hack range: every streamed target (forced by -D" + PROPERTY + "=streamed; the 50 m fallback stays off)"
			: "hack range: every streamed target (Phase 0 h desk verdict, unconfirmed in game; the 50 m fallback switches on if Cyberpunk refuses a far target for range)";
	}

	/** Server stop: the next server checks again (or starts on the forced path). */
	public void reset() {
		this.fallback = "near".equals(this.forced);
		this.why = this.fallback ? "forced by -D" + PROPERTY + "=near" : "";
		this.announced = false;
	}
}
