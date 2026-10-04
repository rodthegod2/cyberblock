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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
 * Megaplan 4-A1: every device block has a blockstate and models the 26.3 deserializers accept, 16 x 16 textures, an item
 * definition and an English name; the eddies items have art; the device_map and shop defaults point at things that exist
 * (vanilla ids through the real registries). Gradle runs tests in mc/.
 */
class DeviceAssetsTest {
	private static final Path RES = Path.of("src/main/resources");
	private static final Path ASSETS = RES.resolve("assets/cyberblock");
	private static final Path DATA = RES.resolve("data/cyberblock/cyberblock");
	private static final List<String> DYES = List.of(
		"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
		"brown", "green", "red", "black");
	private static final List<String> OTHERS = List.of(
		"eye_camera", "net_port", "vending_machine", "traffic_lamp", "lift_platform", "lift_call_button", "turret", "sliding_gate",
		"screen_frame", "crate");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<String> blockIds() {
		List<String> ids = new ArrayList<>();
		DYES.forEach(d -> ids.add("lamp_" + d));
		ids.addAll(OTHERS);
		return ids;
	}

	private static JsonObject json(final Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	private static List<Path> files(final Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
	}

	private static void assertTexture(final String ref, final String label) throws IOException {
		assertTrue(ref.startsWith("cyberblock:block/"), label + " " + ref);
		BufferedImage image = ImageIO.read(ASSETS.resolve("textures/block/" + ref.substring("cyberblock:block/".length()) + ".png").toFile());
		assertEquals(16, image.getWidth(), label);
		assertEquals(16, image.getHeight(), label);
	}

	@Test
	void everyDeviceBlockHasStateModelsTexturesItemAndName() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		Set<String> models = new HashSet<>();
		for (String id : blockIds()) {
			JsonElement state = JsonParser.parseString(Files.readString(ASSETS.resolve("blockstates/" + id + ".json")));
			assertTrue(BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, state).result().isPresent(), id + " blockstate parses");
			state.getAsJsonObject().getAsJsonObject("variants").entrySet().forEach(e -> models.add(
				e.getValue().getAsJsonObject().get("model").getAsString().substring("cyberblock:block/".length())));
			String item = json(ASSETS.resolve("items/" + id + ".json")).getAsJsonObject("model").get("model").getAsString();
			assertTrue(Files.isRegularFile(ASSETS.resolve("models/block/" + item.substring("cyberblock:block/".length()) + ".json")), id + " item model");
			assertTrue(lang.has("block.cyberblock." + id) && !lang.get("block.cyberblock." + id).getAsString().isBlank(), id + " name");
		}

		assertTrue(models.size() > 80, "the models the blockstates use: " + models.size());
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
					assertTexture(ref, model + " " + texture.getKey());
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
	void theEddiesItemsHaveArtModelsAndNames() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String item : List.of("eddies", "eddie_chip")) {
			assertEquals("cyberblock:item/" + item, json(ASSETS.resolve("items/" + item + ".json")).getAsJsonObject("model").get("model").getAsString());
			assertEquals("cyberblock:item/" + item, json(ASSETS.resolve("models/item/" + item + ".json")).getAsJsonObject("textures").get("layer0").getAsString());
			BufferedImage image = ImageIO.read(ASSETS.resolve("textures/item/" + item + ".png").toFile());
			assertEquals(16, image.getWidth(), item);
			assertEquals(16, image.getHeight(), item);
			assertTrue(lang.has("item.cyberblock." + item), item);
		}
	}

	@Test
	void theDeviceMapDefaultsPointAtBlocksAndEntitiesThatExist() throws IOException {
		List<String> own = blockIds();
		int count = 0;
		for (Path file : files(DATA.resolve("device_map"))) {
			JsonObject entry = json(file);
			count++;
			List<String> blocks = new ArrayList<>(List.of(entry.get("block").getAsString()));
			if (entry.has("alt_block")) {
				blocks.add(entry.get("alt_block").getAsString());
			}

			if (entry.has("attachments")) {
				entry.getAsJsonArray("attachments").forEach(a -> blocks.add(a.getAsJsonObject().get("block").getAsString()));
			}

			for (String block : blocks) {
				Identifier id = Identifier.parse(block);
				assertTrue(id.getNamespace().equals("cyberblock") ? own.contains(id.getPath()) : BuiltInRegistries.BLOCK.containsKey(id), file + " " + block);
			}

			if (entry.has("entity")) {
				assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(entry.get("entity").getAsString())), file.toString());
			}
		}

		assertEquals(15, count, "one file per confirmed class and the container");
	}

	@Test
	void theShopsSellItemsThatExistAndTheirTitlesHaveNames() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (Path file : files(DATA.resolve("shop"))) {
			JsonObject shop = json(file);
			assertTrue(lang.has(shop.get("title_key").getAsString()), file + " title");
			List<JsonElement> rows = new ArrayList<>();
			shop.getAsJsonArray("stock").forEach(rows::add);
			if (shop.has("sell")) {
				shop.getAsJsonArray("sell").forEach(rows::add);
			}

			for (JsonElement row : rows) {
				Identifier id = Identifier.parse(row.getAsJsonObject().get("item").getAsString());
				boolean exists = id.getNamespace().equals("cyberblock")
					? Files.isRegularFile(ASSETS.resolve("items/" + id.getPath() + ".json")) : BuiltInRegistries.ITEM.containsKey(id);
				assertTrue(exists, file + " " + id);
			}
		}
	}
}
