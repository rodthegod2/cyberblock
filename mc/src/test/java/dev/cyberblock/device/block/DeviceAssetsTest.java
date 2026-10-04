package dev.cyberblock.device.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The contract between 4B's blocks and 4A's art (megaplan 4-B1, 4-A1): every device blockstate file 4A ships names only this block's
 * properties and values, every state of the block has a model, every {@code cyberblock:} model it uses exists, and every device block
 * has one; the lang has 4A's keys 4B's code uses. Skipped while 4A's assets are not merged.
 */
class DeviceAssetsTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/cyberblock");
	/** 4A's keys 4B's code uses (each also has an English fallback in the code): a rename on 4A's side shows here. */
	static final List<String> KEYS = List.of(
		"cyberblock.device.quest_locked", "cyberblock.device.unsupported", "cyberblock.device.no_link", "cyberblock.device.net_port.phase6",
		"cyberblock.device.broken", "cyberblock.device.container.closed", "cyberblock.device.out_of_range", "cyberblock.shop.title",
		"cyberblock.shop.balance", "cyberblock.shop.buy", "cyberblock.shop.price", "cyberblock.shop.sold_out", "cyberblock.shop.empty",
		"cyberblock.shop.no_space", "item.cyberblock.eddies", "item.cyberblock.eddie_chip");
	/** 4B's own keys 4A's lang does not have yet: shown through their English fallbacks until the lang owner adds them. */
	static final List<String> FALLBACK_ONLY = List.of("cyberblock.device.loot", "cyberblock.shop.stock", "cyberblock.shop.result.ok",
		"cyberblock.shop.result.poor", "cyberblock.shop.result.gone", "cyberblock.shop.result.refused", "block.cyberblock.device_door",
		"block.cyberblock.device_iron_door");

	@Test
	void everyShippedDeviceBlockstateFitsTheBlock() throws IOException {
		List<String> missing = DeviceBlocks.NAMES.stream().filter(name -> !Files.isRegularFile(ASSETS.resolve("blockstates/" + name + ".json"))).toList();
		Assumptions.assumeTrue(missing.size() < DeviceBlocks.NAMES.size(), "4A's device art is not merged yet");
		// once the art is in, a device block without a blockstate would show as the missing-model cube
		assertTrue(missing.isEmpty(), "device blocks without a blockstate: " + missing);
		for (String name : DeviceBlocks.NAMES) {
			Path file = ASSETS.resolve("blockstates/" + name + ".json");
			Block block = DeviceTestBlocks.block(name);
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (json.has("variants")) {
				List<Map<String, String>> keys = new ArrayList<>();
				for (Map.Entry<String, JsonElement> variant : json.getAsJsonObject("variants").entrySet()) {
					keys.add(parse(name, variant.getKey(), block));
					models(name, variant.getValue());
				}

				for (BlockState state : block.getStateDefinition().getPossibleStates()) {
					assertTrue(keys.stream().anyMatch(k -> matches(k, state)), name + ": no model for " + state);
				}
			} else {
				for (JsonElement part : json.getAsJsonArray("multipart")) {
					models(name, part.getAsJsonObject().get("apply"));
				}
			}
		}
	}

	@Test
	void theLangHasEveryKeyTheDeviceCodeUses() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"))).getAsJsonObject();
		Assumptions.assumeTrue(lang.has("item.cyberblock.eddies") || lang.has("cyberblock.device.quest_locked"), "4A's device lang is not merged yet");
		for (String key : KEYS) {
			assertTrue(lang.has(key), key);
		}

		for (String name : DeviceBlocks.NAMES) {
			String key = "block.cyberblock." + name;
			assertTrue(lang.has(key) || FALLBACK_ONLY.contains(key), key);
		}
	}

	/** "a=b,c=d" (or "") as a map, every name a property of the block and every value one of its values. */
	private static Map<String, String> parse(final String name, final String key, final Block block) {
		Map<String, String> out = new HashMap<>();
		if (key.isEmpty()) {
			return out;
		}

		for (String pair : key.split(",")) {
			String[] kv = pair.split("=", 2);
			Property<?> property = block.getStateDefinition().getProperty(kv[0]);
			assertTrue(property != null && kv.length == 2 && property.getValue(kv[1]).isPresent(), name + ": bad variant key part " + pair);
			out.put(kv[0], kv[1]);
		}

		return out;
	}

	private static boolean matches(final Map<String, String> key, final BlockState state) {
		for (Map.Entry<String, String> e : key.entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition().getProperty(e.getKey());
			if (property == null || !name(property, state).equals(e.getValue())) {
				return false;
			}
		}

		return true;
	}

	private static <T extends Comparable<T>> String name(final Property<T> property, final BlockState state) {
		return property.getName(state.getValue(property));
	}

	/** Every cyberblock: model a variant names exists (vanilla models are the player's own and are not checked). */
	private static void models(final String name, final JsonElement variant) {
		for (JsonElement v : variant.isJsonArray() ? variant.getAsJsonArray().asList() : List.of(variant)) {
			String model = v.getAsJsonObject().get("model").getAsString();
			if (model.startsWith("cyberblock:")) {
				Path file = ASSETS.resolve("models/" + model.substring("cyberblock:".length()) + ".json");
				assertTrue(Files.isRegularFile(file), name + ": missing model " + model);
			}
		}
	}
}
