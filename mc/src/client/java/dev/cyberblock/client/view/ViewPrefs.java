package dev.cyberblock.client.view;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The third-person camera options (megaplan section 1.9 {@code prefs}): distance 1.5..8 m, shoulder offset -1..1 m (to the
 * right), FOV 0 (the game's own) or 50..110, auto-recenter, vehicle camera distance 0 (the game's own) or 3..14 m, and the step-up
 * assist. Every value is clamped into its range, so whatever a file or a slider holds, the DLL gets a valid message.
 */
public record ViewPrefs(double dist, double shoulder, double fov, boolean recenter, double vdist, boolean stepup) {
	public static final double DIST_MIN = 1.5, DIST_MAX = 8.0, SHOULDER_MAX = 1.0, FOV_MIN = 50.0, FOV_MAX = 110.0, VDIST_MIN = 3.0, VDIST_MAX = 14.0;
	private static final double DEFAULT_DIST = 3.2, DEFAULT_SHOULDER = 0.45;
	/** The DLL's defaults (megaplan Q-D1). */
	public static final ViewPrefs DEFAULT = new ViewPrefs(DEFAULT_DIST, DEFAULT_SHOULDER, 0.0, true, 0.0, true);

	public ViewPrefs {
		dist = clamp(dist, DIST_MIN, DIST_MAX, DEFAULT_DIST);
		shoulder = clamp(shoulder, -SHOULDER_MAX, SHOULDER_MAX, DEFAULT_SHOULDER);
		fov = gameOr(fov, FOV_MIN, FOV_MAX);
		vdist = gameOr(vdist, VDIST_MIN, VDIST_MAX);
	}

	private static double clamp(final double v, final double min, final double max, final double fallback) {
		return Double.isFinite(v) ? Math.clamp(v, min, max) : fallback;
	}

	/** 0 (or anything not above 0, or not finite) means the game's own value; otherwise clamped into min..max. */
	private static double gameOr(final double v, final double min, final double max) {
		return Double.isFinite(v) && v > 0.0 ? Math.clamp(v, min, max) : 0.0;
	}

	/**
	 * The wire message: {"t":"prefs","dist":..,"shoulder":..,"fov":..,"recenter":..,"vdist":..,"stepup":..,"debug":..}, all six
	 * values plus {@code debug}, the view debug flag (DebugFlags.view(): the DLL adds {@code cam.vdbg} while it is on). The flag
	 * is always sent, so the DLL also hears it go off; it is not a camera option and never goes into the config file.
	 */
	public JsonObject message(final boolean debug) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "prefs");
		this.fill(m);
		m.addProperty("debug", debug);
		return m;
	}

	/** The six values as a JSON object (the config file). */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		this.fill(o);
		return o;
	}

	private void fill(final JsonObject o) {
		o.addProperty("dist", round(dist));
		o.addProperty("shoulder", round(shoulder));
		o.addProperty("fov", round(fov));
		o.addProperty("recenter", recenter);
		o.addProperty("vdist", round(vdist));
		o.addProperty("stepup", stepup);
	}

	/** Reads the six values; a missing or wrong-typed one keeps its default, a number out of range is clamped. */
	public static ViewPrefs fromJson(final JsonObject o) {
		return new ViewPrefs(number(o, "dist", DEFAULT.dist), number(o, "shoulder", DEFAULT.shoulder), number(o, "fov", DEFAULT.fov),
			bool(o, "recenter", DEFAULT.recenter), number(o, "vdist", DEFAULT.vdist), bool(o, "stepup", DEFAULT.stepup));
	}

	private static double number(final JsonObject o, final String key, final double fallback) {
		return o.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : fallback;
	}

	private static boolean bool(final JsonObject o, final String key, final boolean fallback) {
		JsonElement e = o.get(key);
		return e instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
	}

	/** Centimetre steps: a slider's 3.2000000000000006 goes on the wire as 3.2. */
	private static double round(final double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	public ViewPrefs withDist(final double v) {
		return new ViewPrefs(v, shoulder, fov, recenter, vdist, stepup);
	}

	public ViewPrefs withShoulder(final double v) {
		return new ViewPrefs(dist, v, fov, recenter, vdist, stepup);
	}

	public ViewPrefs withFov(final double v) {
		return new ViewPrefs(dist, shoulder, v, recenter, vdist, stepup);
	}

	public ViewPrefs withRecenter(final boolean v) {
		return new ViewPrefs(dist, shoulder, fov, v, vdist, stepup);
	}

	public ViewPrefs withVdist(final double v) {
		return new ViewPrefs(dist, shoulder, fov, recenter, v, stepup);
	}

	public ViewPrefs withStepup(final boolean v) {
		return new ViewPrefs(dist, shoulder, fov, recenter, vdist, v);
	}
}
