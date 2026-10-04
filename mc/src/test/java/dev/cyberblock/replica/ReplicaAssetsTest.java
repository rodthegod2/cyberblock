package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Megaplan 3-A1: every replica block has its blockstate, model, item definition, texture and name; the palette points only at them. */
class ReplicaAssetsTest {
	private static final Path RES = Path.of("src/main/resources");
	private static final Path ASSETS = RES.resolve("assets/cyberblock");
	private static final Path PALETTE = RES.resolve("data/cyberblock/cyberblock/material_palette/default.json");

	private static final List<String> DYES = List.of(
		"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
		"brown", "green", "red", "black");
	private static final List<String> MATERIALS = List.of(
		"concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal", "brick", "tile", "ground", "water");

	/** The ids 3R registers, in material_palette code order (code = index + 1). */
	private static List<String> blockIds() {
		List<String> ids = new ArrayList<>();
		MATERIALS.forEach(m -> ids.add("nc_" + m));
		DYES.forEach(d -> ids.add("nc_neon_" + d));
		return ids;
	}

	private static JsonObject json(final Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	@Test
	void everyBlockHasStateModelItemTextureAndName() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String id : blockIds()) {
			String model = "cyberblock:block/" + id;
			assertEquals(model, json(ASSETS.resolve("blockstates/" + id + ".json"))
				.getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString(), id);
			assertEquals(model, json(ASSETS.resolve("items/" + id + ".json")).getAsJsonObject("model").get("model").getAsString(), id);
			JsonObject blockModel = json(ASSETS.resolve("models/block/" + id + ".json"));
			String texture = blockModel.getAsJsonObject("textures").get("all").getAsString();
			assertEquals("cyberblock:block/" + id, texture);
			BufferedImage image = ImageIO.read(ASSETS.resolve("textures/block/" + id + ".png").toFile());
			assertEquals(16, image.getWidth(), id);
			assertEquals(16, image.getHeight(), id);
			assertTrue(lang.has("block.cyberblock." + id) && !lang.get("block.cyberblock." + id).getAsString().isBlank(), id);
			if (id.startsWith("nc_neon_")) {
				assertEquals(15, blockModel.getAsJsonArray("elements").get(0).getAsJsonObject().get("light_emission").getAsInt(), id);
			}
		}
	}

	@Test
	void theNeutralFigureSkinIsA64x64Png() throws IOException {
		BufferedImage skin = ImageIO.read(ASSETS.resolve("textures/entity/figure/neutral.png").toFile());
		assertEquals(64, skin.getWidth());
		assertEquals(64, skin.getHeight());
		assertEquals(0xFF, skin.getRGB(12, 12) >>> 24, "the head front is painted");
	}

	@Test
	void theDefaultPaletteHasCodes0To26AndOnlyPointsAtBlocksThatExist() throws IOException {
		JsonObject palette = json(PALETTE);
		assertEquals(1, palette.get("schema").getAsInt());
		JsonArray codes = palette.getAsJsonArray("codes");
		List<String> ids = blockIds();
		assertEquals(27, codes.size());
		for (int code = 0; code < codes.size(); code++) {
			JsonObject entry = codes.get(code).getAsJsonObject();
			assertEquals(code, entry.get("code").getAsInt());
			assertEquals("cyberblock:" + (code == 0 ? ids.get(0) : ids.get(code - 1)), entry.get("block").getAsString());
		}

		List<String> references = new ArrayList<>();
		for (JsonElement match : palette.getAsJsonArray("match")) {
			assertFalse(match.getAsJsonObject().getAsJsonArray("names").isEmpty());
			references.add(match.getAsJsonObject().get("block").getAsString());
		}

		JsonObject heuristic = palette.getAsJsonObject("heuristic");
		references.add(heuristic.get("water").getAsString());
		references.addAll(heuristic.getAsJsonObject("default").asMap().values().stream().map(JsonElement::getAsString).toList());
		heuristic.getAsJsonObject("districts").asMap().values()
			.forEach(set -> set.getAsJsonObject().asMap().values().forEach(b -> references.add(b.getAsString())));
		for (String reference : references) {
			assertTrue(reference.startsWith("cyberblock:") && ids.contains(reference.substring("cyberblock:".length())), reference);
		}
	}

	@Test
	void theKeysOtherPhase3PackagesUseAreThere() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String key : List.of(
			"cyberblock.worldcheck.refused", "cyberblock.meetup.request", "cyberblock.meetup.accept", "cyberblock.meetup.failed",
			"cyberblock.offline.summary", "cyberblock.changes.header", "cyberblock.replica.protected", "cyberblock.ui.atlas.shared",
			"entity.cyberblock.proxy_human", "entity.cyberblock.proxy_vehicle")) {
			assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), key);
		}
	}
}
