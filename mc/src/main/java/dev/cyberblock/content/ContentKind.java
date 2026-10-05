package dev.cyberblock.content;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One kind of content as data (megaplan section 1.7): its files are {@code data/<namespace>/cyberblock/<id>/<name>.json} in any
 * datapack, each one JSON object with {@code "schema": 1}; the entry id is {@code <namespace>:<name>}. A phase registers its kinds
 * once at startup (before the server loads its datapacks). clientSide kinds reach every client through {@code cyberblock:content}
 * on join and after {@code /reload}.
 */
public record ContentKind<T>(String id, Parser<T> parser, boolean clientSide) {
	/** Turns one file's object (schema already checked) into the kind's value; throws IllegalArgumentException with the reason. */
	@FunctionalInterface
	public interface Parser<T> {
		T parse(Identifier id, JsonObject json);
	}

	/**
	 * Every kind of megaplan section 1.7, in the table's order: each has a schema in {@code shared/content-schemas}, defaults under
	 * {@code data/cyberblock/cyberblock/<kind>/} and a loader some package registers at startup ({@link #unregistered()} names a
	 * missing one; the loader logs it). The {@code test} kind of the skeleton and the Afterlife {@code hub} layout (built once, not
	 * reloaded) are not content kinds in this sense.
	 */
	public static final List<String> SECTION_1_7 = List.of("material_palette", "device_map", "shop", "skin_map", "vehicle_map", "daemon",
		"breach_tier", "dialogue", "contract", "ripperdoc", "landmark", "npc_role", "event", "spawn_palette", "heat_tier", "match_mode");
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
	private static final Map<String, ContentKind<?>> KINDS = new ConcurrentHashMap<>();

	public ContentKind {
		if (!ID.matcher(id).matches()) {
			throw new IllegalArgumentException("content kind ids are 1-32 of a-z, 0-9 and _: " + id);
		}
	}

	/** Registers a kind (once per id). */
	public static <T> ContentKind<T> register(final String id, final Parser<T> parser, final boolean clientSide) {
		ContentKind<T> kind = new ContentKind<>(id, parser, clientSide);
		if (KINDS.putIfAbsent(id, kind) != null) {
			throw new IllegalStateException("content kind registered twice: " + id);
		}

		return kind;
	}

	public static @Nullable ContentKind<?> byId(final String id) {
		return KINDS.get(id);
	}

	static Collection<ContentKind<?>> all() {
		return List.copyOf(KINDS.values());
	}

	/** The section 1.7 kinds no package has registered (empty once every phase's module has started). */
	static List<String> unregistered() {
		return SECTION_1_7.stream().filter(id -> !KINDS.containsKey(id)).toList();
	}

	/** Tests only. */
	static void unregister(final String id) {
		KINDS.remove(id);
	}
}
