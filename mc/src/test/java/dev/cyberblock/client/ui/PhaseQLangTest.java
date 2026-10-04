package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan Q-U2: every Phase Q lang key is there, once, and the loading tips are numbered without gaps. */
class PhaseQLangTest {
	private static final Path LANG = Path.of("src/main/resources/assets/cyberblock/lang/en_us.json");

	private static final List<String> KEYS = List.of(
		"cyberblock.outline.erase", "cyberblock.outline.erase.on", "cyberblock.outline.erase.off", "cyberblock.outline.rescued",
		"cyberblock.settings.title", "cyberblock.settings.button", "cyberblock.settings.locked",
		"cyberblock.view.distance", "cyberblock.view.shoulder", "cyberblock.view.fov", "cyberblock.view.recenter",
		"cyberblock.view.vehicle_distance", "cyberblock.stepup", "cyberblock.ui.loading");

	private static JsonObject lang() throws IOException {
		return JsonParser.parseString(Files.readString(LANG)).getAsJsonObject();
	}

	@Test
	void everyPhaseQKeyHasText() throws IOException {
		JsonObject lang = lang();
		for (String key : KEYS) {
			assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), key);
		}

		assertEquals("Cyberblock...", lang.get("cyberblock.settings.button").getAsString());
		assertEquals("Creative can erase the replica permanently", lang.get("cyberblock.outline.erase").getAsString());
	}

	@Test
	void loadingTipsAreNumberedFromOneWithoutGaps() throws IOException {
		JsonObject lang = lang();
		int count = 0;
		while (lang.has(LoadingCover.tipKey(count + 1))) {
			assertFalse(lang.get(LoadingCover.tipKey(count + 1)).getAsString().isBlank());
			count++;
		}

		assertTrue(count >= 5, "at least five tips, found " + count);
		for (String key : lang.keySet()) {
			if (key.startsWith("cyberblock.loading.tip.")) {
				int n = Integer.parseInt(key.substring("cyberblock.loading.tip.".length()));
				assertTrue(n >= 1 && n <= count, key + " is outside the numbered run 1.." + count);
			}
		}
	}

	@Test
	void noKeyIsDefinedTwice() throws IOException {
		Set<String> seen = new HashSet<>();
		for (String line : Files.readAllLines(LANG)) {
			String t = line.strip();
			if (t.startsWith("\"")) {
				String key = t.substring(1, t.indexOf('"', 1));
				assertTrue(seen.add(key), "duplicate key " + key);
			}
		}
	}
}
