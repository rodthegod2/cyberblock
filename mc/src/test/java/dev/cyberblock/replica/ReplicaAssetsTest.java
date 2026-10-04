package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Megaplan 3-A1: every replica block has its blockstate, model, item definition, texture and name; the palette entries point only at them. */
class ReplicaAssetsTest {
	private static final Path RES = Path.of("src/main/resources");
	private static final Path ASSETS = RES.resolve("assets/cyberblock");
	private static final Path PALETTE = RES.resolve("data/cyberblock/cyberblock/material_palette");

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
	void thePaletteHasOneEntryPerCodeAndPerHeuristicClassPointingAtBlocksThatExist() throws IOException {
		List<String> ids = blockIds();
		Set<Integer> codes = new TreeSet<>();
		Set<String> classes = new TreeSet<>();
		for (Path file : paletteFiles()) {
			JsonObject entry = json(file);
			assertEquals(1, entry.get("schema").getAsInt(), file.toString());
			String block = entry.get("block").getAsString();
			assertTrue(block.startsWith("cyberblock:") && ids.contains(block.substring("cyberblock:".length())), block);
			assertFalse(entry.has("code") && entry.has("heuristic"), file.toString());
			if (entry.has("code")) {
				int code = entry.get("code").getAsInt();
				assertEquals("cyberblock:" + ids.get(code - 1), block, file.toString());
				codes.add(code);
			} else {
				classes.add(entry.get("heuristic").getAsString());
			}
		}

		assertEquals(IntStream.rangeClosed(1, 26).boxed().toList(), List.copyOf(codes));
		assertEquals(new TreeSet<>(List.of("street", "sidewalk", "roof", "offroad", "wall_base", "wall_brick", "wall_trim", "wall_window", "wall",
			"water")), classes);
	}

	/** The shipped entries through 3R's real loader (Palette.parse): skipped until the replica package is merged. */
	@Test
	void theReplicaLoaderParsesEveryShippedEntry() throws Exception {
		Class<?> palette;
		try {
			palette = Class.forName("dev.cyberblock.replica.Palette");
		} catch (ClassNotFoundException e) {
			Assumptions.abort("the replica Palette loader (3R) is not merged yet");
			return;
		}

		Method parse = palette.getDeclaredMethod("parse", Identifier.class, JsonObject.class);
		parse.setAccessible(true);
		for (Path file : paletteFiles()) {
			String name = file.getFileName().toString().replace(".json", "");
			assertNotNull(parse.invoke(null, Identifier.fromNamespaceAndPath("cyberblock", name), json(file)), name);
		}
	}

	@Test
	void theKeysOtherPhase3PackagesUseAreThere() throws IOException {
		JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
		for (String key : List.of(
			"cyberblock.meetup.request", "cyberblock.meetup.accept", "cyberblock.meetup.failed", "cyberblock.meetup.arrived",
			"cyberblock.offline.summary", "cyberblock.offline.counts", "cyberblock.changes.header", "cyberblock.figure.hostile",
			"entity.cyberblock.proxy_human", "entity.cyberblock.proxy_vehicle")) {
			assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), key);
		}
	}

	private static List<Path> paletteFiles() throws IOException {
		try (Stream<Path> files = Files.list(PALETTE)) {
			return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
	}
}
