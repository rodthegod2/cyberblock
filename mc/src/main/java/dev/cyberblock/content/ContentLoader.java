package dev.cyberblock.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.ServerNet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The content loader skeleton of megaplan section 1.7. A Fabric server-data reload listener (fabric-resource-loader-v1 3.0.4:
 * {@code ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Identifier, PreparableReloadListener)} with a
 * {@code SimpleReloadListener}: prepare off-thread, apply on the reload's apply executor) reads every registered kind from
 * {@code data/<namespace>/cyberblock/<kind>/*.json} of every datapack (vanilla {@code FileToIdConverter.json}, the top pack wins
 * per id) on server start and on {@code /reload}, swaps in immutable snapshots, logs {@code content: loaded <kind> N
 * (<namespaces>)}, and skips a bad file with {@code content: <kind> <id> skipped: <why>} (never fatal). Clients get the clientSide
 * kinds through {@code cyberblock:content} from Fabric's SYNC_DATA_PACK_CONTENTS event (on join and after every reload).
 */
public final class ContentLoader {
	/** A content file larger than this is skipped. */
	static final int FILE_CAP = 1_000_000;
	public static final int SCHEMA = 1;
	/** The test kind (megaplan 1-N4): {"schema":1, "name": str <= 64 bytes, "colour"?: "#RRGGBB", "pos"?: [x, y, z]}. */
	private static @Nullable ContentKind<TestEntry> test;

	public record TestEntry(String name, int colour, @Nullable Vec3 pos) {
	}

	@FunctionalInterface
	interface Text {
		String read() throws IOException;
	}

	private ContentLoader() {
	}

	/** Common init, once: the test kind, the reload listener and the client sync. Later phases register their kinds before load. */
	public static void init() {
		test = ContentKind.register("test", ContentLoader::parseTest, false);
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Identifier.fromNamespaceAndPath(Cyberblock.ID, "content"),
			new SimpleReloadListener<Map<ContentKind<?>, Content.Snapshot<?>>>() {
				@Override
				protected Map<ContentKind<?>, Content.Snapshot<?>> prepare(final PreparableReloadListener.SharedState state) {
					return loadAll(state.resourceManager());
				}

				@Override
				protected void apply(final Map<ContentKind<?>, Content.Snapshot<?>> loaded, final PreparableReloadListener.SharedState state) {
					loaded.forEach(Content::put);
				}
			});
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> sync(player));
	}

	public static @Nullable ContentKind<TestEntry> testKind() {
		return test;
	}

	static TestEntry parseTest(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "name", 64);
		int colour = json.has("colour") ? Content.colour(json, "colour") : 0xFFFFFF;
		Vec3 pos = null;
		if (json.has("pos")) {
			double[] p = Content.position(json, "pos");
			pos = new Vec3(p[0], p[1], p[2]);
		}

		return new TestEntry(name, colour, pos);
	}

	private static Map<ContentKind<?>, Content.Snapshot<?>> loadAll(final ResourceManager manager) {
		Map<ContentKind<?>, Content.Snapshot<?>> out = new LinkedHashMap<>();
		for (ContentKind<?> kind : ContentKind.all()) {
			FileToIdConverter files = FileToIdConverter.json(Cyberblock.ID + "/" + kind.id());
			Map<Identifier, Text> texts = new TreeMap<>();
			for (Map.Entry<Identifier, Resource> e : files.listMatchingResources(manager).entrySet()) {
				Resource r = e.getValue();
				texts.put(files.fileToId(e.getKey()), r::readAllAsString);
			}

			out.put(kind, load(kind, texts, line -> Cyberblock.LOG.info(line), line -> Cyberblock.LOG.warn(line)));
		}

		return out;
	}

	/** One kind from its files (entry id -> text): every good file is an entry, every bad one a "skipped" line. Pure. */
	static <T> Content.Snapshot<T> load(final ContentKind<T> kind, final Map<Identifier, Text> files, final Consumer<String> info, final Consumer<String> warn) {
		Map<Identifier, T> entries = new TreeMap<>();
		JsonObject client = new JsonObject();
		for (Map.Entry<Identifier, Text> file : new TreeMap<>(files).entrySet()) {
			Identifier id = file.getKey();
			try {
				String text = file.getValue().read();
				if (text.length() > FILE_CAP || text.getBytes(StandardCharsets.UTF_8).length > FILE_CAP) {
					throw new IllegalArgumentException("larger than " + FILE_CAP + " bytes");
				}

				JsonElement parsed = JsonParser.parseString(text);
				if (!parsed.isJsonObject()) {
					throw new IllegalArgumentException("not a JSON object");
				}

				JsonObject json = parsed.getAsJsonObject();
				JsonElement schema = json.get("schema");
				if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber() || schema.getAsDouble() != SCHEMA) {
					throw new IllegalArgumentException("\"schema\" must be " + SCHEMA);
				}

				entries.put(id, kind.parser().parse(id, json));
				if (kind.clientSide()) {
					client.add(id.toString(), json);
				}
			} catch (IOException | RuntimeException e) {
				String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				warn.accept("content: " + kind.id() + " " + id + " skipped: " + why.replace('\n', ' '));
			}
		}

		TreeSet<String> namespaces = new TreeSet<>();
		entries.keySet().forEach(id -> namespaces.add(id.getNamespace()));
		info.accept("content: loaded " + kind.id() + " " + entries.size() + " (" + String.join(", ", namespaces) + ")");
		String clientJson = "";
		if (kind.clientSide()) {
			JsonObject wrapper = new JsonObject();
			wrapper.addProperty("schema", SCHEMA);
			wrapper.add("entries", client);
			clientJson = wrapper.toString();
		}

		return new Content.Snapshot<>(Collections.unmodifiableMap(new LinkedHashMap<>(entries)), clientJson);
	}

	/** Every clientSide kind to one player (cyberblock:content), on join and after each reload. */
	static void sync(final ServerPlayer player) {
		for (ContentKind<?> kind : ContentKind.all()) {
			Content.Snapshot<?> s = kind.clientSide() ? Content.snapshot(kind) : null;
			if (s != null) {
				ServerNet.content(player, kind.id(), s.clientJson());
			}
		}
	}
}
