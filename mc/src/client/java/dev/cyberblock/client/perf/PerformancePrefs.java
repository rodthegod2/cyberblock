package dev.cyberblock.client.perf;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Performance mode (megaplan task 9-F1), this client's own setting: the frame scale (Minecraft renders and exports its picture at
 * 0.5..1 of the host's screen size, sent to the DLL as {@code prefs.scale}; default 1) and the figure cap (only the nearest 16..48
 * Night City figures are drawn; default 48). Every value is clamped into its range, so whatever a file or a slider holds is valid.
 */
public record PerformancePrefs(double scale, int figureCap) {
	public static final double SCALE_MIN = 0.5, SCALE_MAX = 1.0;
	public static final int CAP_MIN = 16, CAP_MAX = 48;
	public static final PerformancePrefs DEFAULT = new PerformancePrefs(SCALE_MAX, CAP_MAX);

	public PerformancePrefs {
		scale = Double.isFinite(scale) ? Math.round(Math.clamp(scale, SCALE_MIN, SCALE_MAX) * 100.0) / 100.0 : SCALE_MAX;
		figureCap = Math.clamp(figureCap, CAP_MIN, CAP_MAX);
	}

	/** Performance mode is on when either value is below its default. */
	public boolean on() {
		return this.scale < SCALE_MAX || this.figureCap < CAP_MAX;
	}

	public PerformancePrefs withScale(final double v) {
		return new PerformancePrefs(v, this.figureCap);
	}

	public PerformancePrefs withFigureCap(final int v) {
		return new PerformancePrefs(this.scale, v);
	}

	/** The config file: {"scale":..,"figureCap":..}. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("scale", this.scale);
		o.addProperty("figureCap", this.figureCap);
		return o;
	}

	/** A missing or wrong-typed value keeps its default; a number out of range is clamped. */
	public static PerformancePrefs fromJson(final JsonObject o) {
		double scale = o.get("scale") instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : DEFAULT.scale;
		int cap = o.get("figureCap") instanceof JsonPrimitive p && p.isNumber() ? (int) Math.clamp(Math.round(p.getAsDouble()), Integer.MIN_VALUE, Integer.MAX_VALUE)
			: DEFAULT.figureCap;
		return new PerformancePrefs(scale, cap);
	}
}
