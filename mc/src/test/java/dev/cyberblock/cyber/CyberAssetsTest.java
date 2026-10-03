package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Every cyber item has its item model definition, model, texture, recipe and name (Gradle runs tests in mc/). */
class CyberAssetsTest {
	private static final Path RES = Path.of("src/main/resources");
	private static final Path ASSETS = RES.resolve("assets/cyberblock");

	/** The 16 cyberware items added in re-test 2 (plan section 1.7; written out so this test does not depend on the enum). */
	private static final List<String> NEW_CYBERWARE = List.of(
		"mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws", "sandevistan", "berserk", "kerenzikov", "optical_camo", "subdermal_armor", "kiroshi_optics", "biomonitor",
		"second_heart", "ram_upgrade");

	/** All 17 wire ids: the 16 above plus the existing cyberdeck, in the section 1.7 order. */
	private static final List<String> CYBERWARE_IDS = List.of(
		"mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system", "reinforced_tendons", "fortified_ankles",
		"lynx_paws", "sandevistan", "berserk", "cyberdeck", "kerenzikov", "optical_camo", "subdermal_armor", "kiroshi_optics",
		"biomonitor", "second_heart", "ram_upgrade");

	private static final List<String> SLOTS = List.of(
		"arms", "legs", "operating_system", "nervous_system", "integumentary", "eyes", "circulatory", "frontal_cortex");

	/** Every key the re-test 2 plan (section 1.10) adds, with the number of format arguments its text takes. */
	private static final Map<String, Integer> MESSAGES = Map.ofEntries(
		Map.entry("cyberblock.skills.bought_free", 1),
		Map.entry("cyberblock.skills.free", 0),
		Map.entry("cyberblock.perks.free.on", 0),
		Map.entry("cyberblock.perks.free.off", 0),
		Map.entry("cyberblock.perks.free.creative", 0),
		Map.entry("cyberblock.level.done", 2),
		Map.entry("cyberblock.level.no_host", 0),
		Map.entry("cyberblock.level.not_standing", 0),
		Map.entry("key.cyberblock.cyberware", 0),
		Map.entry("key.cyberblock.activate", 0),
		Map.entry("cyberblock.cyberware.title", 0),
		Map.entry("cyberblock.cyberware.installed", 1),
		Map.entry("cyberblock.cyberware.removed", 1),
		Map.entry("cyberblock.cyberware.replaced", 2),
		Map.entry("cyberblock.cyberware.selected", 1),
		Map.entry("cyberblock.cyberware.none", 0),
		Map.entry("cyberblock.cyberware.cooldown", 2),
		Map.entry("cyberblock.cyberware.too_close", 0),
		Map.entry("cyberblock.cyberware.not_owned", 0),
		Map.entry("cyberblock.cyberware.second_heart", 0),
		Map.entry("cyberblock.cyberware.biomonitor", 0),
		Map.entry("cyberblock.cyberware.migrated", 1),
		Map.entry("cyberblock.cyberware.hud.active", 0),
		Map.entry("cyberblock.cyberware.tooltip.slot", 1),
		Map.entry("cyberblock.cyberware.tooltip.cooldown", 1),
		Map.entry("cyberblock.cyberware.tooltip.install", 1),
		Map.entry("cyberblock.cyberware.screen.available", 0),
		Map.entry("cyberblock.cyberware.screen.empty", 0),
		Map.entry("cyberblock.hack.no_deck", 0),
		Map.entry("cyberblock.hack.no_deck_os", 2),
		Map.entry("cyberblock.binds.clash", 4));

	private static List<String> items() {
		List<String> items = new ArrayList<>();
		for (Hack h : Hack.values()) {
			items.add(h.item());
		}

		items.addAll(List.of("cyberdeck", "ram_shard", "mantis_blade", "gorilla_gauntlet"));
		items.addAll(NEW_CYBERWARE);
		return items;
	}

	private static JsonObject json(final Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	/** Counts the format specifiers ({@code %s}, {@code %1$s}, ...) in a lang text; {@code %%} is a literal percent sign. */
	private static int arguments(final String text) {
		int count = 0;
		for (int i = 0; i < text.length() - 1; i++) {
			if (text.charAt(i) != '%') {
				continue;
			}

			if (text.charAt(i + 1) == '%') {
				i++;
				continue;
			}

			count++;
		}

		return count;
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
	void newCyberwareRecipesAreShapelessEquipmentWithTheBaseParts() throws IOException {
		for (String id : NEW_CYBERWARE) {
			JsonObject recipe = json(RES.resolve("data/cyberblock/recipe/" + id + ".json"));
			assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString(), id);
			assertEquals("equipment", recipe.get("category").getAsString(), id);
			List<String> ingredients = new ArrayList<>();
			JsonArray array = recipe.getAsJsonArray("ingredients");
			array.forEach(e -> ingredients.add(e.getAsString()));
			assertTrue(ingredients.contains("minecraft:redstone"), id + " redstone");
			assertTrue(ingredients.contains("minecraft:copper_ingot"), id + " copper");
			assertTrue(ingredients.size() <= 9, id + " fits the crafting grid");
			assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt(), id);
		}
	}

	@Test
	void everyCyberwareHasItsTexts() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String id : CYBERWARE_IDS) {
			assertTrue(lang.has("item.cyberblock." + id), id + " name");
			assertTrue(lang.has("cyberware.cyberblock." + id + ".mc"), id + " .mc");
			assertTrue(lang.has("cyberware.cyberblock." + id + ".cp"), id + " .cp");
			assertTrue(!lang.get("cyberware.cyberblock." + id + ".mc").getAsString().isBlank(), id + " .mc text");
			assertTrue(!lang.get("cyberware.cyberblock." + id + ".cp").getAsString().isBlank(), id + " .cp text");
		}

		for (String slot : SLOTS) {
			assertTrue(lang.has("cyberblock.cyberware.slot." + slot), slot + " slot name");
		}
	}

	@Test
	void everyNewMessageIsPresentWithTheRightArguments() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		MESSAGES.forEach((key, args) -> {
			assertTrue(lang.has(key), key);
			assertEquals(args, arguments(lang.get(key).getAsString()), key + " format arguments");
		});
	}

	@Test
	void rewordedTextsPointAtTheImplants() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		assertTrue(lang.get("cyberblock.hack.no_deck").getAsString().contains("Operating System"), "no_deck names the slot");
		for (String perk : List.of("double_jump", "sandevistan", "optical_camo")) {
			assertTrue(lang.get("perk.cyberblock." + perk + ".mc").getAsString().contains("upgrades your"), perk + " .mc");
			assertTrue(lang.has("perk.cyberblock." + perk + ".cp"), perk + " .cp");
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
	void guiTexturesAreThere() throws IOException {
		for (String name : List.of("ram_bar", "skill_tree_bg", "cyberware_bg")) {
			assertTrue(Files.isRegularFile(ASSETS.resolve("textures/gui/" + name + ".png")), name);
		}

		for (String name : List.of("skill_body", "skill_reflexes", "skill_technical", "skill_intelligence", "skill_cool", "perk_token")) {
			assertTrue(Files.isRegularFile(ASSETS.resolve("textures/item/" + name + ".png")), name);
		}

		BufferedImage bg = ImageIO.read(ASSETS.resolve("textures/gui/cyberware_bg.png").toFile());
		assertEquals(256, bg.getWidth(), "cyberware_bg width");
		assertEquals(192, bg.getHeight(), "cyberware_bg height");
		for (String id : NEW_CYBERWARE) {
			BufferedImage sprite = ImageIO.read(ASSETS.resolve("textures/item/" + id + ".png").toFile());
			assertEquals(16, sprite.getWidth(), id + " sprite width");
			assertEquals(16, sprite.getHeight(), id + " sprite height");
		}
	}
}
