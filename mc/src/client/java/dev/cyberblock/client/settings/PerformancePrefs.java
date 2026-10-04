package dev.cyberblock.client.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Cyberblock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The settings screen's Performance tab (megaplan 9-S1; the values 9-F1's Performance mode applies): the frame export scale in percent
 * (50..100 by 5: Minecraft renders and exports at that share of the window, the compositor upscales) and the figure cap (16..48 by 4:
 * the nearest Night City figures drawn). Client only, saved on this PC in {@code config/cyberblock/performance.json}. Whoever applies
 * them registers with {@link #listen}; the client logs once which parts of the game read them.
 */
public record PerformancePrefs(int scale, int figureCap) {
	public static final int SCALE_MIN = 50, SCALE_MAX = 100, SCALE_STEP = 5, CAP_MIN = 16, CAP_MAX = 48, CAP_STEP = 4;
	/** Full resolution and the 48 nearest figures: the game as it was before Phase 9. */
	public static final PerformancePrefs DEFAULT = new PerformancePrefs(SCALE_MAX, CAP_MAX);

	/** Each value is clamped into its range and snapped down to its step, so a hand-edited file still gives a valid pair. */
	public PerformancePrefs {
		scale = snap(scale, SCALE_MIN, SCALE_MAX, SCALE_STEP);
		figureCap = snap(figureCap, CAP_MIN, CAP_MAX, CAP_STEP);
	}

	private static int snap(final int v, final int min, final int max, final int step) {
		int c = Math.clamp(v, min, max);
		return min + (c - min) / step * step;
	}

	/** The frame export scale as a factor, 0.5..1.0. */
	public double scaleFactor() {
		return this.scale / 100.0;
	}

	public PerformancePrefs withScale(final int v) {
		return new PerformancePrefs(v, this.figureCap);
	}

	public PerformancePrefs withFigureCap(final int v) {
		return new PerformancePrefs(this.scale, v);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("scale", this.scale);
		o.addProperty("figureCap", this.figureCap);
		return o;
	}

	/** A missing or wrong-typed value keeps its default; a number out of range is clamped. */
	public static PerformancePrefs fromJson(final JsonObject o) {
		return new PerformancePrefs(number(o, "scale", DEFAULT.scale), number(o, "figureCap", DEFAULT.figureCap));
	}

	private static int number(final JsonObject o, final String key, final int fallback) {
		return o.get(key) instanceof JsonPrimitive p && p.isNumber() && Double.isFinite(p.getAsDouble()) ? (int) Math.round(p.getAsDouble()) : fallback;
	}

	// ---- the saved values (client thread) ----


	private record Listener(String who, Consumer<PerformancePrefs> apply) {
	}

	private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private static volatile PerformancePrefs current = DEFAULT;

	/** The values in force now. */
	public static PerformancePrefs current() {
		return current;
	}

	/**
	 * {@code apply} gets the current values at once and every change from then on (client thread). {@code who} names the reader for
	 * the client's one log line ("the frame export", "the figures").
	 */
	public static void listen(final String who, final Consumer<PerformancePrefs> apply) {
		listeners.add(new Listener(who, apply));
		apply.accept(current);
	}

	/** Who reads the values, for the log line: "the frame export, the figures", or "" when nothing does yet. */
	static String readers() {
		return String.join(", ", listeners.stream().map(Listener::who).toList());
	}

	/** config/cyberblock/performance.json (looked up when used, so the record itself needs no Fabric loader). */
	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("cyberblock").resolve("performance.json");
	}

	/** The config file, read at client start. */
	static void load() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}

		try {
			current = fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			Cyberblock.LOG.warn("settings: could not read {}, using the defaults: {}", file.getFileName(), e.toString());
		}
	}

	/** A Performance row changed: saved and handed to every reader. */
	static void set(final PerformancePrefs p) {
		if (p.equals(current)) {
			return;
		}

		current = p;
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, p.toJson().toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cyberblock.LOG.warn("settings: could not save the performance values: {}", e.toString());
		}

		Cyberblock.LOG.info("settings: performance frame scale {} %, figure cap {}", p.scale, p.figureCap);
		for (Listener l : listeners) {
			l.apply.accept(p);
		}
	}
}
