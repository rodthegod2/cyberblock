package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
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
		Map.entry("cyberblock.binds.clash", 4),
		Map.entry("key.cyberblock.arms", 0),
		Map.entry("key.cyberblock.camo", 0),
		Map.entry("cyberblock.hack.fired", 1),
		Map.entry("cyberblock.hack.busy", 1),
		Map.entry("cyberblock.cyberware.none.os", 1),
		Map.entry("cyberblock.cyberware.none.arms", 1),
		Map.entry("cyberblock.cyberware.none.camo", 1),
		Map.entry("cyberblock.binds.vanilla_unbound", 2),
		Map.entry("cyberblock.cyberware.tooltip.key", 1),
		Map.entry("cyberblock.cyberware.screen.os_deck", 0),
		Map.entry("cyberblock.cyberware.screen.os_combat", 0),
		Map.entry("cyberblock.cyberware.lunge", 0),
		Map.entry("cyberblock.cyberware.slam", 0),
		Map.entry("cyberblock.cyberware.lash", 0),
		Map.entry("cyberblock.cyberware.no_target", 0),
		Map.entry("cyberblock.sandevistan.hud", 0),
		Map.entry("menu.cyberblock.play", 0),
		Map.entry("cyberblock.view.third", 0),
		Map.entry("cyberblock.view.first", 0));

	/** The re-test 3 UI texts (plan 1.12, "cyberblock.ui." prefix removed) with their format argument counts. */
	private static final Map<String, Integer> UI_MESSAGES = Map.ofEntries(
		Map.entry("pause.title", 0), Map.entry("pause.back", 0), Map.entry("pause.save", 0), Map.entry("pause.saving_locked", 0),
		Map.entry("pause.load_last", 0), Map.entry("pause.map", 0), Map.entry("pause.journal", 0), Map.entry("pause.messages", 0),
		Map.entry("pause.cp_menu", 0), Map.entry("pause.options", 0), Map.entry("pause.quit", 0), Map.entry("pause.quit_confirm", 0),
		Map.entry("atlas.title", 0), Map.entry("atlas.you", 0), Map.entry("atlas.track", 0), Map.entry("atlas.fast_travel", 1),
		Map.entry("atlas.fast_travel_off", 0),
		Map.entry("journal.title", 0), Map.entry("journal.main", 0), Map.entry("journal.side", 0), Map.entry("journal.gig", 0),
		Map.entry("journal.other", 0), Map.entry("journal.track", 0), Map.entry("journal.tracked", 0), Map.entry("journal.done", 0),
		Map.entry("journal.empty", 0),
		Map.entry("phone.title", 0), Map.entry("phone.contacts", 0), Map.entry("phone.reply", 0), Map.entry("phone.empty", 0),
		Map.entry("call.incoming", 1), Map.entry("call.answer", 2), Map.entry("call.active", 1), Map.entry("call.ended", 0),
		Map.entry("notify.saving", 0), Map.entry("notify.saved", 0),
		Map.entry("money", 1), Map.entry("time", 2), Map.entry("speed", 1), Map.entry("quest.optional", 0),
		Map.entry("quest.distance", 1), Map.entry("prompt.hold", 0), Map.entry("screen.unavailable", 0));

	/** The 12 notification kinds of the wire protocol (section 1.1), each with a "cyberblock.ui.notify.<kind>" title. */
	private static final List<String> NOTIFY_KINDS = List.of(
		"quest", "item", "money", "xp", "level", "sms", "shard", "zone", "wanted", "warning", "save", "info");

	/** The 14 pass-through menu names (section 1.11), each with a "cyberblock.ui.pass.<name>" label. */
	private static final List<String> PASS_NAMES = List.of(
		"vendor", "ripperdoc", "stash", "wardrobe", "crafting", "breach", "arcade", "braindance", "tutorial", "settings", "saves",
		"death", "loading", "other");

	/** The four arm implants that get 3D models (section 1.8) and the advancements of section 1.8. */
	private static final List<String> ARM_IDS = List.of("mantis_blades", "gorilla_arms", "monowire", "projectile_launch_system");
	private static final List<String> ADVANCEMENTS = List.of(
		"root", "first_implant", "dual_os", "first_quickhack", "every_quickhack", "sandevistan_kill");

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
			JsonObject flat = definition.getAsJsonObject("model");
			if (ARM_IDS.contains(item)) {
				flat = flat.getAsJsonObject("fallback");
			}

			assertEquals("cyberblock:item/" + item, flat.get("model").getAsString(), item);
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

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyUiTextIsPresentWithTheRightArguments() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		UI_MESSAGES.forEach((key, args) -> {
			String full = "cyberblock.ui." + key;
			assertTrue(lang.has(full), full);
			assertEquals(args, arguments(lang.get(full).getAsString()), full + " format arguments");
		});

		for (String kind : NOTIFY_KINDS) {
			assertTrue(lang.has("cyberblock.ui.notify." + kind), "notify " + kind);
		}

		for (String name : PASS_NAMES) {
			assertTrue(lang.has("cyberblock.ui.pass." + name), "pass " + name);
		}
	}

	@Test
	void removedKeysAreGone() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		assertTrue(!lang.has("cyberblock.cyberware.selected"), "selected");
		assertTrue(!lang.has("cyberblock.hack.no_deck_os"), "no_deck_os");
	}

	@Test
	void armImplantsHaveSelectDefinitionsModelsAndTextures() throws IOException {
		for (String id : ARM_IDS) {
			JsonObject model = json(ASSETS.resolve("items/" + id + ".json")).getAsJsonObject("model");
			assertEquals("minecraft:select", model.get("type").getAsString(), id);
			assertEquals("minecraft:custom_model_data", model.get("property").getAsString(), id);
			Map<String, String> cases = new java.util.HashMap<>();
			model.getAsJsonArray("cases").forEach(c -> {
				JsonObject o = c.getAsJsonObject();
				cases.put(o.get("when").getAsString(), o.getAsJsonObject("model").get("model").getAsString());
			});
			assertEquals(Map.of("arm", "cyberblock:item/" + id + "_arm", "arm_out", "cyberblock:item/" + id + "_arm_out"), cases, id);
			assertEquals("cyberblock:item/" + id, model.getAsJsonObject("fallback").get("model").getAsString(), id);

			BufferedImage texture = ImageIO.read(ASSETS.resolve("textures/item/arm_" + id + ".png").toFile());
			assertEquals(32, texture.getWidth(), id + " arm texture width");
			assertEquals(32, texture.getHeight(), id + " arm texture height");
			for (String suffix : List.of("_arm", "_arm_out")) {
				checkArmModel(id + suffix, "cyberblock:item/arm_" + id);
			}
		}
	}

	/** Plan 1.8 arm frame: every element inside -16..32, from <= to, uv inside the 16-unit atlas, emission 1..15. */
	private static void checkArmModel(final String name, final String texture) throws IOException {
		JsonObject model = json(ASSETS.resolve("models/item/" + name + ".json"));
		assertEquals(texture, model.getAsJsonObject("textures").get("0").getAsString(), name + " texture");
		try (var reader = Files.newBufferedReader(ASSETS.resolve("models/item/" + name + ".json"))) {
			assertTrue(CuboidModel.fromStream(reader) != null, name + " parses with the 26.3 model deserializer");
		}

		JsonArray elements = model.getAsJsonArray("elements");
		assertTrue(elements.size() >= 8, name + " has real geometry");
		boolean glows = false;
		for (var element : elements) {
			JsonObject e = element.getAsJsonObject();
			String label = name + "/" + e.get("name").getAsString();
			for (int axis = 0; axis < 3; axis++) {
				double from = e.getAsJsonArray("from").get(axis).getAsDouble();
				double to = e.getAsJsonArray("to").get(axis).getAsDouble();
				assertTrue(from >= -16 && to <= 32 && from <= to, label + " axis " + axis);
			}

			if (e.has("light_emission")) {
				glows = true;
				int light = e.get("light_emission").getAsInt();
				assertTrue(light >= 1 && light <= 15, label + " light_emission");
			}

			for (var face : e.getAsJsonObject("faces").entrySet()) {
				JsonObject f = face.getValue().getAsJsonObject();
				assertEquals("#0", f.get("texture").getAsString(), label + " " + face.getKey());
				JsonArray uv = f.getAsJsonArray("uv");
				assertEquals(4, uv.size(), label + " uv");
				uv.forEach(v -> assertTrue(v.getAsDouble() >= 0 && v.getAsDouble() <= 16, label + " uv range"));
			}
		}

		assertTrue(glows, name + " has glowing parts");
	}

	@Test
	void deployedArmModelsReachPastTheFistWhileFoldedOnesStayOnTheArm() throws IOException {
		for (String id : List.of("mantis_blades", "monowire", "projectile_launch_system")) {
			assertTrue(lowestY(id + "_arm_out") < lowestY(id + "_arm") - 4, id + " extends when out");
		}

		assertTrue(lowestY("mantis_blades_arm_out") <= -10, "blades reach y -10 or lower");
		assertTrue(lowestY("monowire_arm_out") <= -10, "the wire hangs to y -10 or lower");
	}

	private static double lowestY(final String model) throws IOException {
		double lowest = Double.MAX_VALUE;
		for (var element : json(ASSETS.resolve("models/item/" + model + ".json")).getAsJsonArray("elements")) {
			lowest = Math.min(lowest, element.getAsJsonObject().getAsJsonArray("from").get(1).getAsDouble());
		}

		return lowest;
	}

	@Test
	void cyberLookOverlaysAndHudSpritesAreThere() throws IOException {
		for (String name : List.of("kiroshi_eyes", "subdermal_armor", "reinforced_tendons")) {
			BufferedImage overlay = ImageIO.read(ASSETS.resolve("textures/entity/cyber/" + name + ".png").toFile());
			assertEquals(64, overlay.getWidth(), name + " width");
			assertEquals(64, overlay.getHeight(), name + " height");
			int opaque = 0;
			for (int y = 0; y < 64; y++) {
				for (int x = 0; x < 64; x++) {
					if ((overlay.getRGB(x, y) >>> 24) > 0) {
						opaque++;
					}
				}
			}

			assertTrue(opaque > 10, name + " has pixels");
			assertTrue(opaque < 64 * 64 / 4, name + " is mostly transparent");
		}

		Map<String, int[]> sprites = Map.of("wanted_star", new int[] {9, 9}, "wanted_star_empty", new int[] {9, 9},
			"phone", new int[] {16, 16}, "radar_frame", new int[] {80, 80});
		for (var sprite : sprites.entrySet()) {
			BufferedImage image = ImageIO.read(ASSETS.resolve("textures/gui/ui/" + sprite.getKey() + ".png").toFile());
			assertEquals(sprite.getValue()[0], image.getWidth(), sprite.getKey() + " width");
			assertEquals(sprite.getValue()[1], image.getHeight(), sprite.getKey() + " height");
		}

		BufferedImage icon = ImageIO.read(ASSETS.resolve("icon.png").toFile());
		assertEquals(128, icon.getWidth(), "icon width");
		assertEquals(128, icon.getHeight(), "icon height");
	}

	@Test
	void advancementsFormTheSection18TreeWithTextsAndParse() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		Map<String, String> parents = Map.of("first_implant", "root", "dual_os", "first_implant", "first_quickhack", "root",
			"every_quickhack", "first_quickhack", "sandevistan_kill", "dual_os");
		for (String id : ADVANCEMENTS) {
			JsonObject advancement = json(RES.resolve("data/cyberblock/advancement/" + id + ".json"));
			JsonObject display = advancement.getAsJsonObject("display");
			assertTrue(lang.has("advancements.cyberblock." + id + ".title"), id + " title");
			assertTrue(lang.has("advancements.cyberblock." + id + ".description"), id + " description");
			assertEquals("advancements.cyberblock." + id + ".title", display.getAsJsonObject("title").get("translate").getAsString(), id);
			if (parents.containsKey(id)) {
				assertEquals("cyberblock:" + parents.get(id), advancement.get("parent").getAsString(), id + " parent");
			} else {
				assertTrue(!advancement.has("parent"), id + " is the root");
				assertTrue(display.has("background"), "visible roots need a background");
			}

			String icon = display.getAsJsonObject("icon").get("id").getAsString();
			assertTrue(Files.isRegularFile(ASSETS.resolve("items/" + icon.substring("cyberblock:".length()) + ".json")), id + " icon");
			String trigger = id.equals("root") ? "minecraft:tick" : "minecraft:impossible";
			advancement.getAsJsonObject("criteria").entrySet().forEach(c ->
				assertEquals(trigger, c.getValue().getAsJsonObject().get("trigger").getAsString(), id + " " + c.getKey()));

			// the real 26.3 codec accepts the file (our items are not registered in a unit test, so the icon is swapped)
			display.getAsJsonObject("icon").addProperty("id", "minecraft:stone");
			var parsed = Advancement.CODEC.parse(JsonOps.INSTANCE, advancement);
			assertTrue(parsed.result().isPresent(), id + " codec: " + parsed.error().map(e -> e.message()).orElse(""));
		}

		JsonObject every = json(RES.resolve("data/cyberblock/advancement/every_quickhack.json")).getAsJsonObject("criteria");
		List<String> wires = new ArrayList<>();
		for (Hack h : Hack.values()) {
			wires.add(h.wire());
		}

		assertEquals(wires.stream().sorted().toList(), every.keySet().stream().sorted().toList(), "one criterion per chip");
	}

	@Test
	void noVanillaAssetsAreShipped() throws IOException {
		assertTrue(!Files.exists(RES.resolve("assets/minecraft")), "assets/minecraft must not exist (legal rule)");
		try (Stream<Path> files = Files.walk(RES)) {
			List<String> stray = files.filter(Files::isRegularFile).map(p -> RES.relativize(p).toString().replace('\\', '/'))
				.filter(p -> p.startsWith("assets/minecraft/") || p.startsWith("net/") || p.startsWith("com/mojang")).toList();
			assertEquals(List.of(), stray);
		}
	}
}
