package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
	private static final String DEFAULTS = "{\"schema\":1, \"allowHosts\":true, \"hosts\":[], \"maxHosts\":4, \"requireMod\":true, \"joinHelp\":\"\", "
		+ "\"rate\":1.0, \"sanityRadius\":160, \"creativeErasesReplica\":false, \"pvpDefault\":false, \"backups\":{\"daily\":true, \"keep\":7, \"hour\":5}, "
		+ "\"webMap\":false, \"joinPassword\":\"\"}";

	@AfterEach
	void restore() {
		ServerConfig.set(ServerConfig.DEFAULT);
	}

	private static ServerConfig parse(final String json, final List<String> problems) {
		return ServerConfig.parse(JsonParser.parseString(json).getAsJsonObject(), problems::add);
	}

	@Test
	void theSection113DefaultsParseCleanly() {
		List<String> problems = new ArrayList<>();
		assertEquals(ServerConfig.DEFAULT, parse(DEFAULTS, problems));
		assertEquals(List.of(), problems);
		assertEquals(4, ServerConfig.DEFAULT.maxHosts(), "Phase 3: four hosts at once");
		assertEquals(160, ServerConfig.DEFAULT.sanityRadius());
		assertEquals(new ServerConfig.Backups(true, 7, 5), ServerConfig.DEFAULT.backups());
	}

	/** Megaplan 1.13: creativeErasesReplica true in server.json wins over the world's setting; the default false leaves it to the world. */
	@Test
	void creativeErasesReplicaTrueForcesTheOutlineSettingOn() {
		assertFalse(dev.cyberblock.OutlineRules.creativeErases());
		assertEquals(dev.cyberblock.OutlineRules.Source.WORLD, dev.cyberblock.OutlineRules.creativeErasesSource());
		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("creativeErasesReplica", true);
		ServerConfig.set(ServerConfig.parse(o, p -> fail(p)));
		assertTrue(dev.cyberblock.OutlineRules.creativeErases());
		assertEquals(dev.cyberblock.OutlineRules.Source.SERVER_JSON, dev.cyberblock.OutlineRules.creativeErasesSource());
	}

	@Test
	void theWrittenDefaultsReadBackAsTheDefaults() {
		List<String> problems = new ArrayList<>();
		assertEquals(ServerConfig.DEFAULT, parse(ServerConfig.toJson(ServerConfig.DEFAULT), problems));
		assertEquals(List.of(), problems);
	}

	@Test
	void badValuesAreReportedAndReplacedByTheirDefault() {
		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("maxHosts", 0);
		o.addProperty("rate", "fast");
		o.addProperty("sanityRadius", 99999);
		o.addProperty("requireMod", 1);
		o.addProperty("joinHelp", "x".repeat(300));
		JsonObject backups = new JsonObject();
		backups.addProperty("daily", false);
		backups.addProperty("keep", 0);
		backups.addProperty("hour", 24);
		o.add("backups", backups);
		List<String> problems = new ArrayList<>();
		ServerConfig c = ServerConfig.parse(o, problems::add);
		assertEquals(4, c.maxHosts());
		assertEquals(1.0, c.rate());
		assertEquals(160, c.sanityRadius());
		assertTrue(c.requireMod());
		assertEquals("", c.joinHelp());
		assertEquals(new ServerConfig.Backups(false, 7, 5), c.backups(), "each backups field on its own");
		assertEquals(7, problems.size(), String.join("\n", problems));
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("maxHosts must be a whole number from 1 to 16")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("backups.hour")), problems.toString());
	}

	@Test
	void goodValuesAreKept() {
		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("maxHosts", 3);
		o.addProperty("rate", 2.5);
		o.addProperty("allowHosts", false);
		o.addProperty("joinHelp", "Ask Rod for the modpack link");
		o.add("hosts", JsonParser.parseString("[\"CbHost1\", \"00000000-0000-0001-0000-000000000001\", \"not a name!\", 5]"));
		List<String> problems = new ArrayList<>();
		ServerConfig c = ServerConfig.parse(o, problems::add);
		assertEquals(3, c.maxHosts());
		assertEquals(2.5, c.rate());
		assertFalse(c.allowHosts());
		assertEquals("Ask Rod for the modpack link", c.joinHelp());
		assertEquals(List.of("CbHost1", "00000000-0000-0001-0000-000000000001"), c.hosts());
		assertEquals(2, problems.size(), "two bad hosts entries skipped");
		assertTrue(c.listsHost("cbhost1", new UUID(9, 9)), "names match in any case");
		assertTrue(c.listsHost("Someone", new UUID(1, 1)), "or by UUID");
		assertFalse(c.listsHost("CbGuest1", new UUID(3, 3)));
	}

	@Test
	void theJoinPasswordIsOffByDefaultAndTrimmed() {
		assertEquals("", ServerConfig.DEFAULT.joinPassword());
		assertFalse(ServerConfig.DEFAULT.joinPasswordOn());
		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("joinPassword", "  hunter2 ");
		List<String> problems = new ArrayList<>();
		ServerConfig c = ServerConfig.parse(o, problems::add);
		assertEquals("hunter2", c.joinPassword());
		assertTrue(c.joinPasswordOn());
		assertEquals(List.of(), problems);
		o.addProperty("joinPassword", "p".repeat(ServerConfig.JOIN_PASSWORD_CAP));
		assertEquals(ServerConfig.JOIN_PASSWORD_CAP, ServerConfig.parse(o, problems::add).joinPassword().length(), "64 characters are fine");
		o.addProperty("joinPassword", "pass with spaces");
		assertEquals("pass with spaces", ServerConfig.parse(o, problems::add).joinPassword());
		assertEquals(List.of(), problems);
	}

	@Test
	void aBadJoinPasswordIsReportedWithoutItsValueAndLeavesItOff() {
		String secret = "hunter2";
		for (String bad : new String[] {secret + "x".repeat(ServerConfig.JOIN_PASSWORD_CAP), secret + "\tx",secret + "\u00A7a", secret + "\u007F"}) {
			JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
			o.addProperty("joinPassword", bad);
			List<String> problems = new ArrayList<>();
			ServerConfig c = ServerConfig.parse(o, problems::add);
			assertEquals("", c.joinPassword(), bad);
			assertEquals(1, problems.size(), problems.toString());
			assertTrue(problems.get(0).startsWith("joinPassword must be a string of at most 64 characters"), problems.get(0));
			assertFalse(problems.get(0).contains(secret), "a problem never quotes the password");
		}

		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("joinPassword", 1234);
		List<String> problems = new ArrayList<>();
		assertEquals("", ServerConfig.parse(o, problems::add).joinPassword());
		assertFalse(problems.get(0).contains("1234"));
		o.remove("joinPassword");
		problems.clear();
		assertEquals("", ServerConfig.parse(o, problems::add).joinPassword(), "an older file without the field: off");
		assertEquals(List.of("joinPassword is missing; using the default (no join password)"), problems);
	}

	@Test
	void theConfigNeverPrintsTheJoinPassword() {
		JsonObject o = JsonParser.parseString(DEFAULTS).getAsJsonObject();
		o.addProperty("joinPassword", "hunter2");
		ServerConfig c = ServerConfig.parse(o, p -> {});
		assertFalse(c.toString().contains("hunter2"), c.toString());
		assertTrue(c.toString().contains("joinPassword=on"));
		assertTrue(ServerConfig.DEFAULT.toString().contains("joinPassword=off"));
		assertTrue(ServerConfig.toJson(ServerConfig.DEFAULT).contains("\"joinPassword\": \"\""), "the defaults file has the field, empty");
	}

	@Test
	void missingFieldsTakeTheirDefault() {
		List<String> problems = new ArrayList<>();
		ServerConfig c = parse("{\"schema\":1,\"maxHosts\":2}", problems);
		assertEquals(2, c.maxHosts());
		assertEquals(ServerConfig.DEFAULT.sanityRadius(), c.sanityRadius());
		assertFalse(problems.isEmpty());
	}

	@Test
	void theFirstStartWritesTheDefaults(@TempDir final Path dir) throws Exception {
		Path file = dir.resolve("cyberblock").resolve("server.json");
		ServerConfig c = ServerConfig.load(file);
		assertEquals(ServerConfig.DEFAULT, c);
		assertSame(c, ServerConfig.get());
		assertTrue(Files.exists(file));
		List<String> problems = new ArrayList<>();
		assertEquals(ServerConfig.DEFAULT, parse(Files.readString(file, StandardCharsets.UTF_8), problems));
		assertEquals(List.of(), problems);
	}

	@Test
	void aFileThatIsNotJsonGivesTheDefaultsAndIsLeftAlone(@TempDir final Path dir) throws Exception {
		Path file = dir.resolve("server.json");
		Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
		assertEquals(ServerConfig.DEFAULT, ServerConfig.load(file));
		assertEquals("{ not json", Files.readString(file, StandardCharsets.UTF_8), "the admin's file is never overwritten");
	}

	@Test
	void anEditedFileIsRead(@TempDir final Path dir) throws Exception {
		Path file = dir.resolve("server.json");
		Files.writeString(file, DEFAULTS.replace("\"maxHosts\":4", "\"maxHosts\":3"), StandardCharsets.UTF_8);
		assertEquals(3, ServerConfig.load(file).maxHosts());
		assertEquals(3, ServerConfig.get().maxHosts());
	}
}
