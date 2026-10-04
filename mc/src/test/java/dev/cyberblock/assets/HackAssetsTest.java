package dev.cyberblock.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Megaplan 6-A1: the six Phase 6 blocks have blockstates and models the 26.3 deserializers accept, 16 x 16 textures, item
 * definitions and English names; the five Phase 6 items have art, models and names; the recipes use ingredients that exist (vanilla ids
 * through the real registries). Registering the blocks and items is 6R's and 6I's job. Gradle runs tests in mc/.
 */
class HackAssetsTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/cyberblock");
	private static final Path DATA = Path.of("src/main/resources/data/cyberblock");
	private static final List<String> BLOCKS = List.of("device_relay", "proximity_sensor", "heat_sensor", "breach_port", "charge", "cyberdeck_block");
	private static final List<String> ITEMS = List.of("cyberdeck_link", "traffic_tool", "net_map", "comms_panel", "firewall");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static JsonObject json(final Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	private static void assertSquare(final Path png, final String label) throws IOException {
		BufferedImage image = ImageIO.read(png.toFile());
		assertEquals(16, image.getWidth(), label);
		assertEquals(16, image.getHeight(), label);
	}

	@Test
	void everyHackBlockHasStateModelsTexturesItemAndName() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		Set<String> models = new HashSet<>();
		for (String id : BLOCKS) {
			JsonElement state = JsonParser.parseString(Files.readString(ASSETS.resolve("blockstates/" + id + ".json")));
			assertTrue(BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, state).result().isPresent(), id + " blockstate parses");
			state.getAsJsonObject().getAsJsonObject("variants").entrySet().forEach(e -> models.add(
				e.getValue().getAsJsonObject().get("model").getAsString().substring("cyberblock:block/".length())));
			String item = json(ASSETS.resolve("items/" + id + ".json")).getAsJsonObject("model").get("model").getAsString();
			assertTrue(Files.isRegularFile(ASSETS.resolve("models/block/" + item.substring("cyberblock:block/".length()) + ".json")), id + " item model");
			assertTrue(lang.has("block.cyberblock." + id) && !lang.get("block.cyberblock." + id).getAsString().isBlank(), id + " name");
			assertTrue(lang.has("item.cyberblock." + id + ".tooltip"), id + " tooltip");
		}

		assertEquals(4 + 2 + 6 + 2 + 2 + 5, models.size(), "the models the blockstates use");
		for (String model : models) {
			Path path = ASSETS.resolve("models/block/" + model + ".json");
			try (var reader = Files.newBufferedReader(path)) {
				assertNotNull(CuboidModel.fromStream(reader), model + " parses with the 26.3 model deserializer");
			}

			JsonObject textures = json(path).getAsJsonObject("textures");
			for (var texture : textures.entrySet()) {
				String ref = texture.getValue().getAsString();
				if (ref.startsWith("#")) {
					assertTrue(textures.has(ref.substring(1)), model + " " + ref);
				} else {
					assertTrue(ref.startsWith("cyberblock:block/"), model + " " + ref);
					assertSquare(ASSETS.resolve("textures/block/" + ref.substring("cyberblock:block/".length()) + ".png"), model + " " + texture.getKey());
				}
			}

			for (JsonElement element : json(path).getAsJsonArray("elements")) {
				for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
					assertTrue(textures.has(face.getValue().getAsJsonObject().get("texture").getAsString().substring(1)), model + " " + face.getKey());
				}
			}
		}
	}

	@Test
	void theHackItemsHaveArtModelsDefinitionsAndNames() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String item : ITEMS) {
			assertEquals("cyberblock:item/" + item, json(ASSETS.resolve("items/" + item + ".json")).getAsJsonObject("model").get("model").getAsString());
			assertEquals("cyberblock:item/" + item, json(ASSETS.resolve("models/item/" + item + ".json")).getAsJsonObject("textures").get("layer0").getAsString());
			assertSquare(ASSETS.resolve("textures/item/" + item + ".png"), item);
			assertTrue(lang.has("item.cyberblock." + item) && lang.has("item.cyberblock." + item + ".tooltip"), item);
		}

		assertTrue(lang.has("cyberware.cyberblock.firewall.mc") && lang.has("cyberware.cyberblock.firewall.cp"));
	}

	@Test
	void theRecipesMakeOurThingsFromIngredientsThatExist() throws IOException {
		List<String> everything = new java.util.ArrayList<>(BLOCKS);
		everything.addAll(ITEMS);
		for (String name : everything) {
			JsonObject recipe = json(DATA.resolve("recipe/" + name + ".json"));
			assertEquals("cyberblock:" + name, recipe.getAsJsonObject("result").get("id").getAsString(), name);
			for (JsonElement ingredient : recipe.getAsJsonArray("ingredients")) {
				Identifier id = Identifier.parse(ingredient.getAsString());
				// Unit tests never run the mod's init: our own items are checked by their item definition
				boolean exists = id.getNamespace().equals("cyberblock")
					? Files.isRegularFile(ASSETS.resolve("items/" + id.getPath() + ".json")) : BuiltInRegistries.ITEM.containsKey(id);
				assertTrue(exists, name + " needs " + id);
			}
		}
	}
}
