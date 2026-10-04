package dev.cyberblock.content;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentLoaderTest {
	private final List<String> info = new ArrayList<>(), warn = new ArrayList<>();
	private ContentKind<ContentLoader.TestEntry> test;

	@BeforeEach
	void kinds() {
		this.test = ContentKind.register("t_test", ContentLoader::parseTest, false);
	}

	@AfterEach
	void forget() {
		ContentKind.unregister("t_test");
		ContentKind.unregister("t_client");
		ContentKind.unregister("t_dup");
		Content.clear();
	}

	private static Identifier id(final String s) {
		return Identifier.parse(s);
	}

	private static Map<Identifier, ContentLoader.Text> files(final String... idThenText) {
		Map<Identifier, ContentLoader.Text> out = new LinkedHashMap<>();
		for (int i = 0; i < idThenText.length; i += 2) {
			String text = idThenText[i + 1];
			out.put(id(idThenText[i]), () -> text);
		}

		return out;
	}

	@Test
	void goodFilesLoadFromEveryNamespaceSortedById() {
		Content.Snapshot<ContentLoader.TestEntry> s = ContentLoader.load(this.test, files(
			"zfriend:b", "{\"schema\":1,\"name\":\"Bee\",\"colour\":\"#00ff80\",\"pos\":[1,64,-2.5]}",
			"cyberblock:a", "{\"schema\":1,\"name\":\"Ay\"}"), this.info::add, this.warn::add);
		assertEquals(List.of(id("cyberblock:a"), id("zfriend:b")), List.copyOf(s.entries().keySet()));
		assertEquals(new ContentLoader.TestEntry("Ay", 0xFFFFFF, null), s.entries().get(id("cyberblock:a")));
		assertEquals(new ContentLoader.TestEntry("Bee", 0x00FF80, new Vec3(1, 64, -2.5)), s.entries().get(id("zfriend:b")));
		assertEquals(List.of("content: loaded t_test 2 (cyberblock, zfriend)"), this.info);
		assertEquals(List.of(), this.warn);
		assertEquals("", s.clientJson(), "not a client kind");
	}

	@Test
	void badFilesAreSkippedWithTheReasonNeverFatal() {
		Content.Snapshot<ContentLoader.TestEntry> s = ContentLoader.load(this.test, files(
			"cyberblock:no_schema", "{\"name\":\"x\"}",
			"cyberblock:schema2", "{\"schema\":2,\"name\":\"x\"}",
			"cyberblock:broken", "{ nope",
			"cyberblock:array", "[1]",
			"cyberblock:long", "{\"schema\":1,\"name\":\"" + "n".repeat(65) + "\"}",
			"cyberblock:colour", "{\"schema\":1,\"name\":\"x\",\"colour\":\"red\"}",
			"cyberblock:pos", "{\"schema\":1,\"name\":\"x\",\"pos\":[1,2]}",
			"cyberblock:ok", "{\"schema\":1,\"name\":\"fine\"}"), this.info::add, this.warn::add);
		assertEquals(List.of(id("cyberblock:ok")), List.copyOf(s.entries().keySet()));
		assertEquals(7, this.warn.size(), String.join("\n", this.warn));
		assertTrue(this.warn.contains("content: t_test cyberblock:schema2 skipped: \"schema\" must be 1"), this.warn.toString());
		assertTrue(this.warn.contains("content: t_test cyberblock:long skipped: name is longer than 64 bytes"), this.warn.toString());
		assertTrue(this.warn.contains("content: t_test cyberblock:colour skipped: colour must be #RRGGBB"), this.warn.toString());
		assertEquals(List.of("content: loaded t_test 1 (cyberblock)"), this.info);
	}

	@Test
	void anUnreadableFileIsSkipped() {
		Map<Identifier, ContentLoader.Text> f = new LinkedHashMap<>();
		f.put(id("cyberblock:io"), () -> {
			throw new IOException("disk on fire");
		});
		Content.Snapshot<ContentLoader.TestEntry> s = ContentLoader.load(this.test, f, this.info::add, this.warn::add);
		assertTrue(s.entries().isEmpty());
		assertEquals(List.of("content: t_test cyberblock:io skipped: disk on fire"), this.warn);
		assertEquals(List.of("content: loaded t_test 0 ()"), this.info);
	}

	@Test
	void anOversizedFileIsSkipped() {
		String big = "{\"schema\":1,\"name\":\"x\",\"pad\":\"" + "p".repeat(ContentLoader.FILE_CAP) + "\"}";
		ContentLoader.load(this.test, files("cyberblock:big", big), this.info::add, this.warn::add);
		assertEquals(1, this.warn.size());
		assertTrue(this.warn.get(0).endsWith("larger than 1000000 bytes"));
	}

	@Test
	void snapshotsAreImmutableAndSwappedWhole() {
		Content.Snapshot<ContentLoader.TestEntry> first = ContentLoader.load(this.test, files("cyberblock:a", "{\"schema\":1,\"name\":\"one\"}"), s -> {}, s -> {});
		Content.put(this.test, first);
		Map<Identifier, ContentLoader.TestEntry> seen = Content.all(this.test);
		assertThrows(UnsupportedOperationException.class, () -> seen.put(id("cyberblock:z"), null));
		Content.put(this.test, ContentLoader.load(this.test, files("cyberblock:b", "{\"schema\":1,\"name\":\"two\"}"), s -> {}, s -> {}));
		assertEquals(List.of(id("cyberblock:a")), List.copyOf(seen.keySet()), "a reader's map never changes under it");
		assertNull(Content.get(this.test, id("cyberblock:a")));
		assertEquals("two", Content.get(this.test, id("cyberblock:b")).name());
	}

	@Test
	void beforeAnyLoadAKindIsEmpty() {
		assertTrue(Content.all(this.test).isEmpty());
	}

	@Test
	void aClientKindCarriesItsEntriesForCyberblockContent() {
		ContentKind<String> client = ContentKind.register("t_client", (id, json) -> Content.string(json, "skin", 32), true);
		Content.Snapshot<String> s = ContentLoader.load(client, files(
			"cyberblock:ganger", "{\"schema\":1,\"skin\":\"tyger\"}",
			"cyberblock:bad", "{\"schema\":1}"), this.info::add, this.warn::add);
		JsonObject json = JsonParser.parseString(s.clientJson()).getAsJsonObject();
		assertEquals(1, json.get("schema").getAsInt());
		assertEquals(JsonParser.parseString("{\"cyberblock:ganger\":{\"schema\":1,\"skin\":\"tyger\"}}"), json.get("entries"), "only good entries");
	}

	@Test
	void kindIdsAreCheckedAndRegisteredOnce() {
		assertThrows(IllegalArgumentException.class, () -> new ContentKind<>("Bad Id", (id, json) -> json, false));
		assertThrows(IllegalArgumentException.class, () -> new ContentKind<>("x".repeat(33), (id, json) -> json, false));
		ContentKind.register("t_dup", (id, json) -> json, false);
		assertThrows(IllegalStateException.class, () -> ContentKind.register("t_dup", (id, json) -> json, false));
		assertNotNull(ContentKind.byId("t_dup"));
	}

	@Test
	void helpersCheckTheCommonShapes() {
		JsonObject o = JsonParser.parseString("{\"s\":\"\\u20ac\\u20ac\",\"c\":\"#A1b2C3\",\"p\":[0,20001,0],\"q\":[1,2,3]}").getAsJsonObject();
		assertEquals("€€", Content.string(o, "s", 6));
		assertThrows(IllegalArgumentException.class, () -> Content.string(o, "s", 5), "caps count UTF-8 bytes");
		assertEquals(0xA1B2C3, Content.colour(o, "c"));
		assertThrows(IllegalArgumentException.class, () -> Content.position(o, "p"), "y beyond 20000");
		assertArrayEquals(new double[] {1, 2, 3}, Content.position(o, "q"));
		assertThrows(IllegalArgumentException.class, () -> Content.string(o, "missing", 5));
	}
}
