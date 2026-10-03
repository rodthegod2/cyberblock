package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every cyber item has its item model definition, model, texture, recipe and name (Gradle runs tests in mc/). */
class CyberAssetsTest {
	private static final Path RES = Path.of("src/main/resources");
	private static final Path ASSETS = RES.resolve("assets/cyberblock");

	private static List<String> items() {
		List<String> items = new ArrayList<>();
		for (Hack h : Hack.values()) {
			items.add(h.item());
		}

		items.addAll(List.of("cyberdeck", "ram_shard", "mantis_blade", "gorilla_gauntlet"));
		return items;
	}

	private static JsonObject json(final Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	@Test
	void everyItemHasItsAssets() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String item : items()) {
			JsonObject definition = json(ASSETS.resolve("items/" + item + ".json"));
			assertEquals("cyberblock:item/" + item, definition.getAsJsonObject("model").get("model").getAsString(), item);
			JsonObject model = json(ASSETS.resolve("models/item/" + item + ".json"));
			assertEquals("cyberblock:item/" + item, model.getAsJsonObject("textures").get("layer0").getAsString(), item);
			assertTrue(Files.isRegularFile(ASSETS.resolve("textures/item/" + item + ".png")), item + " texture");
			JsonObject recipe = json(RES.resolve("data/cyberblock/recipe/" + item + ".json"));
			assertEquals("cyberblock:" + item, recipe.getAsJsonObject("result").get("id").getAsString(), item);
			assertTrue(lang.has("item.cyberblock." + item), item + " name");
		}
	}

	@Test
	void everyHackHasADescription() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (Hack h : Hack.values()) {
			assertTrue(lang.has("hack.cyberblock." + h.wire()), h.wire());
		}
	}

	@Test
	void guiTexturesAreThere() {
		for (String name : List.of("ram_bar", "skill_tree_bg")) {
			assertTrue(Files.isRegularFile(ASSETS.resolve("textures/gui/" + name + ".png")), name);
		}

		for (String name : List.of("skill_body", "skill_reflexes", "skill_technical", "skill_intelligence", "skill_cool", "perk_token")) {
			assertTrue(Files.isRegularFile(ASSETS.resolve("textures/item/" + name + ".png")), name);
		}
	}
}
