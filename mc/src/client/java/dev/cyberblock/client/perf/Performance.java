package dev.cyberblock.client.perf;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.view.ViewClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Performance mode on this client (megaplan task 9-F1): the setting lives in {@code config/cyberblock/performance.json} (client only,
 * never the server's), is read on first use and logged once ({@code performance: frame scale <s>, figure cap <n>}). The frame scale
 * rides on every {@code prefs} to the DLL (ViewClient), which asks Minecraft for a picture that much smaller; the figure cap is
 * FigureBudget's. The settings screen (package 9S) changes it with {@link #set}. Any thread reads it.
 */
public final class Performance {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cyberblock").resolve("performance.json");
	private static volatile PerformancePrefs prefs = load();

	private Performance() {
	}

	public static PerformancePrefs get() {
		return prefs;
	}

	public static double scale() {
		return prefs.scale();
	}

	public static int figureCap() {
		return prefs.figureCap();
	}

	/** A new setting (the settings screen): saved, logged, and the DLL hears the new scale in a prefs at once. */
	public static void set(final PerformancePrefs p) {
		if (p.equals(prefs)) {
			return;
		}

		prefs = p;
		save(p);
		log(p);
		ViewClient.prefsChanged();
	}

	private static PerformancePrefs load() {
		PerformancePrefs p = PerformancePrefs.DEFAULT;
		if (Files.isRegularFile(FILE)) {
			try {
				p = PerformancePrefs.fromJson(JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (IOException | RuntimeException e) {
				Cyberblock.LOG.warn("performance: could not read {}, using the defaults: {}", FILE.getFileName(), e.toString());
			}
		}

		log(p);
		return p;
	}

	private static void log(final PerformancePrefs p) {
		Cyberblock.LOG.info("performance: frame scale {}, figure cap {} ({})", p.scale(), p.figureCap(), p.on() ? "Performance mode on" : "full quality");
	}

	private static void save(final PerformancePrefs p) {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, p.toJson().toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cyberblock.LOG.warn("performance: could not save the setting: {}", e.toString());
		}
	}
}
