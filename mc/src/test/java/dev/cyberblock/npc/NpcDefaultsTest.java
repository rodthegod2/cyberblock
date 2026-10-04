package dev.cyberblock.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Megaplan 7-N1: every shipped default of the Phase 7 kinds this package reads (7A's npc_role, dialogue, contract and ripperdoc files,
 * and the hub layout's figures) parses here, and every role's references resolve. Skipped before 7A's defaults are in the tree.
 */
class NpcDefaultsTest {
	static final Path DATA = Path.of("src/main/resources/data/cyberblock/cyberblock");

	static List<Path> files(final String kind) throws IOException {
		Path dir = DATA.resolve(kind);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}

		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
	}

	static JsonObject read(final Path p) throws IOException {
		return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	static Identifier id(final Path p) {
		return Identifier.fromNamespaceAndPath("cyberblock", p.getFileName().toString().replace(".json", ""));
	}

	@Test
	void everyDefaultParsesAndEveryRoleReferenceResolves() throws IOException {
		List<Path> roles = files("npc_role");
		Assumptions.assumeFalse(roles.isEmpty(), "7A's defaults are not in this tree yet");
		List<Identifier> dialogue = new ArrayList<>(), catalogues = new ArrayList<>(), shops = new ArrayList<>();
		for (Path p : files("dialogue")) {
			Dialogue.parse(id(p), read(p));
			dialogue.add(id(p));
		}

		for (Path p : files("ripperdoc")) {
			Catalogue.parse(id(p), read(p));
			catalogues.add(id(p));
		}

		for (Path p : files("shop")) {
			shops.add(id(p));
		}

		for (Path p : files("contract")) {
			assertDoesNotThrow(() -> Contract.parse(id(p), read(p)), p.toString());
		}

		for (Path p : roles) {
			NpcRole r = NpcRole.parse(id(p), read(p));
			assertTrue(dialogue.contains(r.dialogue()), p + ": its dialogue file");
			if (r.catalogue() != null) {
				assertTrue(catalogues.contains(r.catalogue()), p + ": its catalogue");
			}

			if (r.shop() != null) {
				assertTrue(shops.contains(r.shop()), p + ": its shop");
			}

			r.shopByTag().values().forEach(s -> assertTrue(shops.contains(s), p + ": shop " + s));
		}

		Path hub = DATA.resolve("hub/afterlife.json");
		if (Files.exists(hub)) {
			List<HubNpcs.Figure> figures = HubNpcs.parse(read(hub));
			assertFalse(figures.isEmpty());
			for (HubNpcs.Figure f : figures) {
				assertTrue(roles.stream().anyMatch(p -> id(p).equals(f.role())), f.id() + ": its role");
				if (f.shop() != null) {
					assertTrue(shops.contains(f.shop()), f.id() + ": its shop");
				}
			}
		}
	}
}
