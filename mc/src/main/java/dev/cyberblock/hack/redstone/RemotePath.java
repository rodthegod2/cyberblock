package dev.cyberblock.hack.redstone;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which Phase 0 (h) path remote actions take (megaplan section 4.1 h; docs/notes/phase0-feasibility.md): the documented API reaches
 * every target the host's game has streamed, regardless of V's distance and sight (verdict "yes", not yet confirmed by the in-game CET
 * check H2/H3); the written fallback allows only targets within {@link #NEAR_METRES} of V. The fallback is active when
 * {@code -Dcyberblock.remoteRange=near} forces it, or from the moment Night City has refused {@link #STRIKES} remote actions for range
 * or sight (the runtime feature check). Only a refusal that names a range or sight check counts, for a target more than 50 m from V
 * that its owner listed within the last second: "not in range" (the device is not in the scripts' last list) and "target gone" (not
 * streamed) are the documented path working, not evidence against it. Pure apart from the property read; server thread.
 */
final class RemotePath {
	/** The fallback's reach from V, metres (= Minecraft blocks). */
	static final double NEAR_METRES = 50.0;
	/** A target its owner listed this recently (server ticks: 1 s) is one Night City had streamed when it refused. */
	static final long FRESH_TICKS = 20;
	/** Range or sight refusals it takes to switch to the fallback. */
	static final int STRIKES = 2;
	/** The scripts' answers for a target they do not have now (Devices.reds Act, Hacks.reds): never a range or sight check. */
	private static final Set<String> NOT_STREAMED = Set.of("not in range", "target gone");
	private static final Pattern RANGE_OR_SIGHT = Pattern.compile("\\b(range|sight|too far|distance)\\b");

	enum Mode {
		STREAMED("every target the host's game has streamed (Phase 0 h: documented API, not yet confirmed in game)"),
		NEAR("only targets within 50 m of V in the host's streamed area (Phase 0 h fallback)");

		private final String text;

		Mode(final String text) {
			this.text = text;
		}

		String text() {
			return this.text;
		}
	}

	private Mode mode = Mode.STREAMED;
	private String why = "default";
	private int strikes;

	/** From the system property: "near" forces the fallback, "streamed" (or nothing) starts on the documented path. */
	void configure(final String property) {
		String p = property == null ? "" : property.trim().toLowerCase(Locale.ROOT);
		this.mode = p.equals("near") ? Mode.NEAR : Mode.STREAMED;
		this.strikes = 0;
		this.why = p.equals("near") ? "forced by -Dcyberblock.remoteRange=near" : p.equals("streamed") ? "forced by -Dcyberblock.remoteRange=streamed"
			: "default; switches to the fallback if Night City refuses " + STRIKES + " remote actions for range or sight";
	}

	Mode mode() {
		return this.mode;
	}

	/** The one line naming the active path. */
	String line() {
		return "redstone: remote actions reach " + this.mode.text() + " (" + this.why + ")";
	}

	/** May a remote action reach a target this far from V (metres; NaN: V's place unknown)? */
	boolean reaches(final double distanceToV) {
		return this.mode == Mode.STREAMED || distanceToV <= NEAR_METRES;
	}

	/** A refusal reason that names a range or sight check (whole words), not merely a target the scripts do not have now. */
	static boolean rangeRefusal(final String reason) {
		String r = reason.trim().toLowerCase(Locale.ROOT);
		return !NOT_STREAMED.contains(r) && RANGE_OR_SIGHT.matcher(r).find();
	}

	/**
	 * Night City answered a remote action for a target this far from V; {@code fresh}: its owner listed it within
	 * {@link #FRESH_TICKS}. The {@link #STRIKES}th range or sight refusal of such a target farther than 50 m proves the documented path
	 * wrong and the fallback takes over; an answer of yes from that far clears the count. True when the mode changed now (the caller
	 * logs {@link #line()}).
	 */
	boolean observe(final boolean ok, final String reason, final double distanceToV, final boolean fresh) {
		if (this.mode == Mode.NEAR || !(distanceToV > NEAR_METRES)) {
			return false;
		}

		if (ok) {
			this.strikes = 0;
			return false;
		}

		if (!fresh || !rangeRefusal(reason) || ++this.strikes < STRIKES) {
			return false;
		}

		this.mode = Mode.NEAR;
		this.why = "Night City refused " + STRIKES + " targets for range or sight, the last " + Math.round(distanceToV) + " m from V: \"" + reason + "\"";
		return true;
	}
}
