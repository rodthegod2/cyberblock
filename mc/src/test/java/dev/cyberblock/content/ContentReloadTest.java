package dev.cyberblock.content;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Megaplan 9-C1: every content kind of section 1.7 has a schema, defaults and a loader, and a file edited in a datapack shows up
 * after a reload. The reload here is the real one minus Fabric's listener: a new {@code MultiPackResourceManager} over real pack
 * folders (the shipped resources and a datapack in a temp folder, as {@code /reload} builds one), read by the same {@code
 * ContentLoader.loadAll} the listener calls, with each kind's real parser (found by reflection, and checked against the source's own
 * {@code ContentKind.register} calls so the table cannot drift).
 */
class ContentReloadTest {
	private static final Path DEFAULTS = Path.of("src/main/resources");
	private static final Path SCHEMAS = Path.of("../shared/content-schemas");
	private static final Path EXAMPLE = Path.of("../examples/content-datapack");
	private static final Path SOURCES = Path.of("src/main/java");
	private static final String NS = "reload_test";

	/** kind id -> {class, parse method}: the parsers the packages register (ContentKind.register(id, Class::method, ...)). */
	private static final Map<String, String[]> PARSERS = new LinkedHashMap<>();

	static {
		PARSERS.put("material_palette", new String[] {"dev.cyberblock.replica.Palette", "parse"});
		PARSERS.put("device_map", new String[] {"dev.cyberblock.device.DeviceMap", "parse"});
		PARSERS.put("shop", new String[] {"dev.cyberblock.device.Shops", "parse"});
		PARSERS.put("skin_map", new String[] {"dev.cyberblock.figure.SkinMap", "parse"});
		PARSERS.put("vehicle_map", new String[] {"dev.cyberblock.figure.VehicleMap", "parse"});
		PARSERS.put("daemon", new String[] {"dev.cyberblock.hack.breach.BreachContent", "parseDaemon"});
		PARSERS.put("breach_tier", new String[] {"dev.cyberblock.hack.breach.BreachContent", "parseTier"});
		PARSERS.put("dialogue", new String[] {"dev.cyberblock.npc.Dialogue", "parse"});
		PARSERS.put("contract", new String[] {"dev.cyberblock.npc.Contract", "parse"});
		PARSERS.put("ripperdoc", new String[] {"dev.cyberblock.npc.Catalogue", "parse"});
		PARSERS.put("landmark", new String[] {"dev.cyberblock.holo.Landmarks", "parse"});
		PARSERS.put("npc_role", new String[] {"dev.cyberblock.npc.NpcRole", "parse"});
		PARSERS.put("event", new String[] {"dev.cyberblock.mode.overlord.OverlordContent", "parseEvent"});
		PARSERS.put("spawn_palette", new String[] {"dev.cyberblock.mode.overlord.OverlordContent", "parseSpawn"});
		PARSERS.put("heat_tier", new String[] {"dev.cyberblock.mode.heat.HeatTiers", "parse"});
		PARSERS.put("match_mode", new String[] {"dev.cyberblock.mode.match.MatchMode", "parse"});
	}

	@TempDir
	Path temp;
	private final Map<String, ContentKind<?>> kinds = new LinkedHashMap<>();
	private final List<String> registeredHere = new ArrayList<>();
	private final List<String> info = new ArrayList<>(), warn = new ArrayList<>();
	private final List<MultiPackResourceManager> open = new ArrayList<>();

	@BeforeEach
	void registerTheRealParsers() throws ReflectiveOperationException {
		for (Map.Entry<String, String[]> e : PARSERS.entrySet()) {
			ContentKind<?> existing = ContentKind.byId(e.getKey());
			if (existing != null) {
				this.kinds.put(e.getKey(), existing); // another test's registration: use it, leave it
				continue;
			}

			Method m = Class.forName(e.getValue()[0]).getDeclaredMethod(e.getValue()[1], Identifier.class, JsonObject.class);
			m.setAccessible(true);
			this.kinds.put(e.getKey(), ContentKind.register(e.getKey(), (id, json) -> {
				try {
					return m.invoke(null, id, json);
				} catch (java.lang.reflect.InvocationTargetException ex) {
					throw ex.getCause() instanceof RuntimeException r ? r : new IllegalStateException(ex.getCause());
				} catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}, e.getKey().equals("material_palette")));
			this.registeredHere.add(e.getKey());
		}
	}

	@AfterEach
	void forget() {
		this.registeredHere.forEach(ContentKind::unregister);
		this.open.forEach(MultiPackResourceManager::close);
		Content.clear();
	}

	private static PackResources pack(final String name, final Path folder) {
		return new PathPackResources(new PackLocationInfo(name, Component.literal(name), PackSource.BUILT_IN, Optional.empty()), folder);
	}

	/** What /reload does: a fresh resource manager over the packs (lowest priority first), read by the loader, swapped in whole. */
	private Map<ContentKind<?>, Content.Snapshot<?>> reload(final Path... datapacks) {
		this.info.clear();
		this.warn.clear();
		List<PackResources> packs = new ArrayList<>();
		packs.add(pack("defaults", DEFAULTS));
		for (Path p : datapacks) {
			packs.add(pack(p.getFileName().toString(), p));
		}

		MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
		this.open.add(manager);
		Map<ContentKind<?>, Content.Snapshot<?>> loaded = ContentLoader.loadAll(manager, this.info::add, this.warn::add);
		loaded.forEach(Content::put);
		return loaded;
	}

	private static List<Path> defaultFiles(final String kind) throws IOException {
		try (Stream<Path> s = Files.list(DEFAULTS.resolve("data/cyberblock/cyberblock/" + kind))) {
			return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
	}

	private static String read(final Path p) throws IOException {
		return Files.readString(p, StandardCharsets.UTF_8);
	}

	private Path datapack(final String name) throws IOException {
		Path root = this.temp.resolve(name);
		Files.createDirectories(root);
		Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\": {\"description\": \"t\", \"min_format\": 121, \"max_format\": 121}}");
		return root;
	}

	private Object parse(final String kind, final Identifier id, final String text) {
		return this.kinds.get(kind).parser().parse(id, JsonParser.parseString(text).getAsJsonObject());
	}

	private static int count(final Map<ContentKind<?>, Content.Snapshot<?>> loaded, final String kind) {
		return loaded.entrySet().stream().filter(e -> e.getKey().id().equals(kind)).findFirst().orElseThrow().getValue().entries().size();
	}

	/** Record-by-record equality (a few entries hold arrays, whose own equals is identity). */
	static boolean same(final Object a, final Object b) {
		if (a == null || b == null) {
			return a == b;
		}

		if (a.getClass().isArray()) {
			return b.getClass() == a.getClass() && Arrays.deepEquals(new Object[] {a}, new Object[] {b});
		}

		if (a.getClass().isRecord()) {
			if (a.getClass() != b.getClass()) {
				return false;
			}

			try {
				for (RecordComponent c : a.getClass().getRecordComponents()) {
					Method m = c.getAccessor();
					m.setAccessible(true);
					if (!same(m.invoke(a), m.invoke(b))) {
						return false;
					}
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}

			return true;
		}

		if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
			return ma.keySet().equals(mb.keySet()) && ma.keySet().stream().allMatch(k -> same(ma.get(k), mb.get(k)));
		}

		if (a instanceof List<?> la && b instanceof List<?> lb) {
			if (la.size() != lb.size()) {
				return false;
			}

			for (int i = 0; i < la.size(); i++) {
				if (!same(la.get(i), lb.get(i))) {
					return false;
				}
			}

			return true;
		}

		if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
			return ca.size() == cb.size() && ca.containsAll(cb);
		}

		return a.equals(b);
	}

	@Test
	void everySection17KindHasASchemaDefaultsAndAParser() throws IOException {
		assertEquals(List.copyOf(PARSERS.keySet()), ContentKind.SECTION_1_7, "the table of this test is the table of section 1.7");
		for (String id : ContentKind.SECTION_1_7) {
			assertTrue(Files.isRegularFile(SCHEMAS.resolve(id + ".schema.json")), id + ": no schema in shared/content-schemas");
			assertFalse(defaultFiles(id).isEmpty(), id + ": no defaults under data/cyberblock/cyberblock/" + id);
			assertNotNull(this.kinds.get(id), id + ": no parser");
		}
	}

	@Test
	void theParserTableMatchesTheRegisterCallsInTheSource() throws IOException {
		StringBuilder all = new StringBuilder();
		try (Stream<Path> s = Files.walk(SOURCES)) {
			for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
				all.append(read(p)).append('\n');
			}
		}

		String source = all.toString();
		for (Map.Entry<String, String[]> e : PARSERS.entrySet()) {
			String cls = e.getValue()[0].substring(e.getValue()[0].lastIndexOf('.') + 1);
			Pattern call = Pattern.compile("ContentKind\\.register\\([^;]*?\\b" + cls + "::" + e.getValue()[1] + "\\b");
			assertTrue(call.matcher(source).find(), e.getKey() + ": no ContentKind.register(..., " + cls + "::" + e.getValue()[1] + ", ...) in the source");
		}
	}

	@Test
	void everyShippedDefaultLoadsThroughItsRealParserWithoutASkip() throws IOException {
		Map<ContentKind<?>, Content.Snapshot<?>> loaded = reload();
		assertEquals(List.of(), this.warn, "no skipped file, no missing loader");
		for (String id : ContentKind.SECTION_1_7) {
			assertEquals(defaultFiles(id).size(), count(loaded, id), id + ": a default file did not become an entry");
		}
	}

	@Test
	void aKindNobodyRegisteredIsNamed() {
		ContentKind.unregister("heat_tier");
		this.registeredHere.remove("heat_tier");
		this.kinds.remove("heat_tier");
		reload();
		assertEquals(List.of("content: kind heat_tier has no loader (megaplan section 1.7): its files are ignored"), this.warn);
	}

	@Test
	void everyKindReloadsWhenItsFileIsAddedEditedBrokenAndRemoved() throws IOException {
		for (String id : ContentKind.SECTION_1_7) {
			List<Path> defaults = defaultFiles(id);
			Path pack = datapack("pack_" + id);
			Path file = pack.resolve("data/" + NS + "/cyberblock/" + id + "/probe.json");
			Identifier probe = Identifier.fromNamespaceAndPath(NS, "probe");
			Files.createDirectories(file.getParent());
			int shipped = defaults.size();

			// add: a copy of the first default under a new namespace
			String first = read(defaults.get(0));
			Files.writeString(file, first);
			Map<ContentKind<?>, Content.Snapshot<?>> loaded = reload(pack);
			assertEquals(List.of(), this.warn, id + ": the copy of a default must load");
			assertEquals(shipped + 1, count(loaded, id), id + ": added file missing");
			assertTrue(same(parse(id, probe, first), Content.all(this.kinds.get(id)).get(probe)), id + ": the entry is not what the file says");
			assertTrue(this.info.contains("content: loaded " + id + " " + (shipped + 1) + " (cyberblock, " + NS + ")"), this.info.toString());

			// edit: the file now says what another default says
			if (defaults.size() > 1) {
				String second = read(defaults.get(1));
				Files.writeString(file, second);
				reload(pack);
				assertTrue(same(parse(id, probe, second), Content.all(this.kinds.get(id)).get(probe)), id + ": the edit did not show after the reload");
			}

			// break: the bad file is skipped with its reason, the rest stays
			Files.writeString(file, "{ not json");
			loaded = reload(pack);
			assertEquals(shipped, count(loaded, id), id + ": a broken file must not be an entry");
			assertEquals(1, this.warn.size(), this.warn.toString());
			assertTrue(this.warn.get(0).startsWith("content: " + id + " " + NS + ":probe skipped: "), this.warn.get(0));

			// remove
			Files.delete(file);
			loaded = reload(pack);
			assertEquals(List.of(), this.warn);
			assertEquals(shipped, count(loaded, id), id + ": a removed file must leave");
			assertNull(Content.all(this.kinds.get(id)).get(probe));
		}
	}

	@Test
	void aDatapackReplacesAShippedFileByItsId() throws IOException {
		Path first = defaultFiles("landmark").get(0);
		String name = first.getFileName().toString();
		Path pack = datapack("replace");
		Path file = pack.resolve("data/cyberblock/cyberblock/landmark/" + name);
		Files.createDirectories(file.getParent());
		String edited = read(first).replace("\"name\": \"", "\"name\": \"Edited ");
		assertNotEquals(read(first), edited);
		Files.writeString(file, edited);
		Map<ContentKind<?>, Content.Snapshot<?>> loaded = reload(pack);
		Identifier id = Identifier.fromNamespaceAndPath("cyberblock", name.substring(0, name.length() - 5));
		assertEquals(defaultFiles("landmark").size(), count(loaded, "landmark"), "replaced, not added");
		assertTrue(same(parse("landmark", id, edited), Content.all(this.kinds.get("landmark")).get(id)));
		Files.delete(file);
		reload(pack);
		assertTrue(same(parse("landmark", id, read(first)), Content.all(this.kinds.get("landmark")).get(id)), "the shipped file is back");
	}

	@Test
	void theExampleDatapackLoadsOverTheDefaultsAndAddsItsSixKinds() throws IOException {
		Map<ContentKind<?>, Content.Snapshot<?>> before = reload();
		Map<String, Integer> shipped = new LinkedHashMap<>();
		ContentKind.SECTION_1_7.forEach(k -> shipped.put(k, count(before, k)));
		Map<ContentKind<?>, Content.Snapshot<?>> loaded = reload(EXAMPLE);
		assertEquals(List.of(), this.warn, "every file of the example must load");
		for (String kind : List.of("dialogue", "skin_map", "shop", "landmark", "daemon", "event")) {
			assertEquals(shipped.get(kind) + 1, count(loaded, kind), kind + ": the example's file did not load");
		}

		assertEquals(shipped.get("device_map"), count(loaded, "device_map"), "the vending machine file replaces the shipped one");
		for (String kind : List.of("material_palette", "vehicle_map", "breach_tier", "contract", "ripperdoc", "npc_role", "spawn_palette", "heat_tier", "match_mode")) {
			assertEquals(shipped.get(kind), count(loaded, kind), kind + ": untouched by the example");
		}

		assertNotNull(Content.all(this.kinds.get("landmark")).get(Identifier.fromNamespaceAndPath("neon_crew", "rooftop_diner")));
		assertNotNull(Content.all(this.kinds.get("event")).get(Identifier.fromNamespaceAndPath("neon_crew", "flash_blackout")));
		assertNotNull(Content.all(this.kinds.get("daemon")).get(Identifier.fromNamespaceAndPath("neon_crew", "unlatch")));
		reload();
		assertNull(Content.all(this.kinds.get("daemon")).get(Identifier.fromNamespaceAndPath("neon_crew", "unlatch")), "taking the datapack away takes its content away");
	}

	@Test
	void aClientKindReachesTheClientAndFollowsTheNextReload() throws IOException {
		ContentKind<?> palette = this.kinds.get("material_palette");
		Content.Snapshot<?> server = reload().get(palette);
		assertFalse(server.clientJson().isEmpty());
		Map<Identifier, ?> serverEntries = Content.all(palette);
		Content.clear(); // the client starts with nothing
		ContentLoader.receive("material_palette", server.clientJson(), this.info::add, this.warn::add);
		assertEquals(List.of(), this.warn);
		assertEquals(serverEntries.size(), Content.all(palette).size());
		assertTrue(same(serverEntries, Content.all(palette)), "the client holds what the server holds");

		// a datapack adds a palette entry: the server reloads, the client gets the new payload
		Path pack = datapack("palette");
		Path file = pack.resolve("data/" + NS + "/cyberblock/material_palette/probe.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, read(defaultFiles("material_palette").get(0)));
		Content.Snapshot<?> reloaded = reload(pack).get(palette);
		String payload = reloaded.clientJson();
		int serverCount = reloaded.entries().size();
		Content.clear();
		ContentLoader.receive("material_palette", server.clientJson(), this.info::add, this.warn::add);
		assertEquals(serverEntries.size(), Content.all(palette).size());
		ContentLoader.receive("material_palette", payload, this.info::add, this.warn::add);
		assertEquals(serverCount, Content.all(palette).size(), "the reload's entry reached the client");
		assertNotNull(Content.all(palette).get(Identifier.fromNamespaceAndPath(NS, "probe")));

		// the file goes away: the next payload takes it off the client
		Files.delete(file);
		String after = reload(pack).get(palette).clientJson();
		ContentLoader.receive("material_palette", after, this.info::add, this.warn::add);
		assertEquals(serverEntries.size(), Content.all(palette).size());
		assertNull(Content.all(palette).get(Identifier.fromNamespaceAndPath(NS, "probe")));
	}
}
