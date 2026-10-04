package dev.cyberblock.skin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Megaplan 5-K1 (lang owner 5K): every Phase 5 key is in en_us.json with the same English as the code's fallback. */
class Phase5LangTest {
	private static final Path LANG = Path.of("src/main/resources/assets/cyberblock/lang/en_us.json");
	private static final Map<String, String> KEYS = Map.of(
		"cyberblock.body.male", "male V (classic model)",
		"cyberblock.body.female", "female V (Lucy, slim model)",
		"cyberblock.body.current", "Your cyber skin: %s. Change it with /cyberblock body male|female",
		"cyberblock.body.set", "Your cyber skin is now %s; every player sees it while your Cyberpunk game is linked",
		"cyberblock.body.players_only", "Only a player has a cyber skin body",
		"cyberblock.team.v", "Linked V");

	@Test
	void everyPhase5KeyMatchesItsFallback() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject();
		KEYS.forEach((key, english) -> assertEquals(english, lang.has(key) ? lang.get(key).getAsString() : null, key));
	}
}
