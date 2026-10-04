package dev.cyberblock.mode.van;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Van can be crafted in survival: its recipe makes the registered item from ingredients that exist (Gradle runs tests in mc/). */
class VanAssetsTest {
	private static final Path RES = Path.of("src/main/resources");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void theVanHasARecipeFromIngredientsThatExist() throws IOException {
		JsonObject recipe = JsonParser.parseString(Files.readString(RES.resolve("data/cyberblock/recipe/" + VanModule.ITEM_ID + ".json"))).getAsJsonObject();
		assertEquals("cyberblock:" + VanModule.ITEM_ID, recipe.getAsJsonObject("result").get("id").getAsString());
		for (JsonElement ingredient : recipe.getAsJsonArray("ingredients")) {
			Identifier id = Identifier.parse(ingredient.getAsString());
			// unit tests never run the mod's init: our own items are checked by their item definition
			boolean exists = id.getNamespace().equals("cyberblock")
				? Files.isRegularFile(RES.resolve("assets/cyberblock/items/" + id.getPath() + ".json")) : BuiltInRegistries.ITEM.containsKey(id);
			assertTrue(exists, "the Van needs " + id);
		}
	}
}
