package dev.cyberblock.net.admin;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostsConfigTest {
	@TempDir
	Path dir;

	private Path file(String json) throws Exception {
		Path f = dir.resolve("server.json");
		Files.writeString(f, json, StandardCharsets.UTF_8);
		return f;
	}

	private static JsonObject read(Path f) throws Exception {
		return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void addKeepsEveryOtherKeyAndOrder() throws Exception {
		Path f = file("{\"schema\":1,\"allowHosts\":true,\"hosts\":[\"Rod\"],\"maxHosts\":1,\"joinHelp\":\"see JOIN.md\",\"backups\":{\"daily\":true}}");
		HostsConfig.Result r = HostsConfig.edit(f, "CbHost1", true);
		assertEquals(HostsConfig.Outcome.ADDED, r.outcome());
		assertEquals(List.of("Rod", "CbHost1"), r.hosts());
		JsonObject o = read(f);
		assertEquals(List.of("schema", "allowHosts", "hosts", "maxHosts", "joinHelp", "backups"), List.copyOf(o.keySet()));
		assertEquals("see JOIN.md", o.get("joinHelp").getAsString());
		assertTrue(o.getAsJsonObject("backups").get("daily").getAsBoolean());
		assertEquals(2, o.getAsJsonArray("hosts").size());
	}

	@Test
	void addTwiceIsAlreadyListedIgnoringCase() throws Exception {
		Path f = file("{\"schema\":1,\"hosts\":[\"CbHost1\"]}");
		HostsConfig.Result r = HostsConfig.edit(f, "cbhost1", true);
		assertEquals(HostsConfig.Outcome.ALREADY, r.outcome());
		assertEquals("CbHost1", r.name());
		assertEquals(1, read(f).getAsJsonArray("hosts").size());
	}

	@Test
	void removeIgnoresCaseAndReportsAnEmptyList() throws Exception {
		Path f = file("{\"schema\":1,\"hosts\":[\"CbHost1\"]}");
		HostsConfig.Result r = HostsConfig.edit(f, "CBHOST1", false);
		assertEquals(HostsConfig.Outcome.REMOVED, r.outcome());
		assertEquals("CbHost1", r.name());
		assertEquals(List.of(), r.hosts());
		assertEquals(0, read(f).getAsJsonArray("hosts").size());
		assertEquals(HostsConfig.Outcome.ABSENT, HostsConfig.edit(f, "CbHost1", false).outcome());
	}

	@Test
	void badNameNeverTouchesTheFile() throws Exception {
		String before = "{\"schema\":1,\"hosts\":[]}";
		Path f = file(before);
		assertEquals(HostsConfig.Outcome.BAD_NAME, HostsConfig.edit(f, "x\",\"y", true).outcome());
		assertEquals(before, Files.readString(f, StandardCharsets.UTF_8));
	}

	@Test
	void brokenFileIsRefusedNotOverwritten() throws Exception {
		for (String broken : new String[] {"{not json", "[1,2]", "{\"hosts\":5}", "{\"hosts\":[\"ok\",7]}", ""}) {
			Path f = file(broken);
			assertEquals(HostsConfig.Outcome.BAD_FILE, HostsConfig.edit(f, "CbHost1", true).outcome(), broken);
			assertEquals(broken, Files.readString(f, StandardCharsets.UTF_8));
		}
	}

	@Test
	void missingFileIsCreatedOnAddOnly() throws Exception {
		Path f = dir.resolve("config").resolve("cyberblock").resolve("server.json");
		assertEquals(HostsConfig.Outcome.ABSENT, HostsConfig.edit(f, "CbHost1", false).outcome());
		assertFalse(Files.exists(f));
		assertEquals(HostsConfig.Outcome.ADDED, HostsConfig.edit(f, "CbHost1", true).outcome());
		JsonObject o = read(f);
		assertEquals(1, o.get("schema").getAsInt());
		assertEquals("CbHost1", o.getAsJsonArray("hosts").get(0).getAsString());
	}

	@Test
	void missingHostsKeyIsAdded() throws Exception {
		Path f = file("{\"schema\":1,\"maxHosts\":1}");
		assertEquals(HostsConfig.Outcome.ADDED, HostsConfig.edit(f, "CbHost1", true).outcome());
		assertEquals("CbHost1", read(f).getAsJsonArray("hosts").get(0).getAsString());
	}

	@Test
	void noTempFileIsLeftBehind() throws Exception {
		Path f = file("{\"schema\":1,\"hosts\":[]}");
		HostsConfig.edit(f, "CbHost1", true);
		try (var files = Files.list(dir)) {
			assertEquals(List.of(f), files.toList());
		}
	}

	@Test
	void settingsFallBackToContractDefaults() throws Exception {
		HostsConfig.Settings d = HostsConfig.read(dir.resolve("missing.json"));
		assertEquals(new HostsConfig.Settings(1, true, "", List.of()), d);

		Path f = file("{\"maxHosts\":\"lots\",\"requireMod\":7,\"joinHelp\":[1],\"hosts\":[\"CbHost1\",\"bad name\",3]}");
		assertEquals(new HostsConfig.Settings(1, true, "", List.of("CbHost1")), HostsConfig.read(f));

		Path g = file("{\"maxHosts\":3,\"requireMod\":false,\"joinHelp\":\"Get the modpack from Rod\",\"hosts\":[]}");
		assertEquals(new HostsConfig.Settings(3, false, "Get the modpack from Rod", List.of()), HostsConfig.read(g));

		assertEquals(1, HostsConfig.read(file("{\"maxHosts\":0}")).maxHosts());
		assertEquals(1, HostsConfig.read(file("{not json")).maxHosts());
	}
}
