package dev.cyberblock.hack.redstone;

import java.util.Locale;

/**
 * Which Phase 0 (h) path remote actions take (megaplan section 4.1 h; docs/notes/phase0-feasibility.md): the documented API reaches
 * every target the host's game has streamed, regardless of V's distance and sight (verdict "yes", not yet confirmed by the in-game CET
 * check H2/H3); the written fallback allows only targets within {@link #NEAR_METRES} of V. The fallback is active when
 * {@code -Dcyberblock.remoteRange=near} forces it, or from the moment Night City refuses a remote action for range or sight (the
 * runtime feature check). Pure apart from the property read; server thread.
 */
final class RemotePath {
	/** The fallback's reach from V, metres (= Minecraft blocks). */
	static final double NEAR_METRES = 50.0;

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

	/** From the system property: "near" forces the fallback, "streamed" (or nothing) starts on the documented path. */
	void configure(final String property) {
		String p = property == null ? "" : property.trim().toLowerCase(Locale.ROOT);
		this.mode = p.equals("near") ? Mode.NEAR : Mode.STREAMED;
		this.why = p.equals("near") ? "forced by -Dcyberblock.remoteRange=near" : p.equals("streamed") ? "forced by -Dcyberblock.remoteRange=streamed"
			: "default; switches to the fallback if Night City refuses a remote action for range or sight";
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

	/** A refusal reason that says the target was too far or out of sight (not merely gone from the streamed area). */
	static boolean rangeRefusal(final String reason) {
		String r = reason.toLowerCase(Locale.ROOT);
		return r.contains("range") || r.contains("sight") || r.contains("too far") || r.contains("distance");
	}

	/**
	 * Night City answered a remote action. A range or sight refusal for a target farther than 50 m from V proves the documented path
	 * wrong: the fallback takes over. True when the mode changed now (the caller logs {@link #line()}).
	 */
	boolean observe(final boolean ok, final String reason, final double distanceToV) {
		if (ok || this.mode == Mode.NEAR || !rangeRefusal(reason) || !(distanceToV > NEAR_METRES)) {
			return false;
		}

		this.mode = Mode.NEAR;
		this.why = "Night City refused a target " + Math.round(distanceToV) + " m from V: \"" + reason + "\"";
		return true;
	}
}
