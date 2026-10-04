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

	/** Tests only. */
	static void unregister(final String id) {
		KINDS.remove(id);
	}
}
