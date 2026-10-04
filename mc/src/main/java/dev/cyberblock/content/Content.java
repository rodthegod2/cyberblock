package dev.cyberblock.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The loaded content (megaplan section 1.7): one immutable snapshot per kind, replaced whole on every server start and
 * {@code /reload}, so a reader never sees half a reload. Any thread may read. Also the small helpers parsers use for the common
 * field shapes (byte-capped strings, {@code #RRGGBB} colours, Minecraft {@code [x, y, z]} positions).
 */
public final class Content {
	/** One kind's entries by id (immutable, sorted by id) and the json clients get for a clientSide kind. */
	record Snapshot<T>(Map<Identifier, T> entries, String clientJson) {
	}

	private static final Map<String, Snapshot<?>> SNAPSHOTS = new ConcurrentHashMap<>();
	private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");

	private Content() {
	}

	/** Every entry of the kind (empty before the first load). Immutable. */
	@SuppressWarnings("unchecked")
	public static <T> Map<Identifier, T> all(final ContentKind<T> kind) {
		Snapshot<?> s = SNAPSHOTS.get(kind.id());
		return s == null ? Map.of() : (Map<Identifier, T>) s.entries();
	}

	public static <T> @Nullable T get(final ContentKind<T> kind, final Identifier id) {
		return all(kind).get(id);
	}

	static void put(final ContentKind<?> kind, final Snapshot<?> snapshot) {
		SNAPSHOTS.put(kind.id(), snapshot);
	}

	static @Nullable Snapshot<?> snapshot(final ContentKind<?> kind) {
		return SNAPSHOTS.get(kind.id());
	}

	/** Loaded entries per registered kind id, sorted (for {@code /cyberblock status}, megaplan 2-M1). */
	public static Map<String, Integer> counts() {
		Map<String, Integer> out = new TreeMap<>();
		for (ContentKind<?> kind : ContentKind.all()) {
			out.put(kind.id(), all(kind).size());
		}

		return out;
	}

	/** Tests only. */
	static void clear() {
		SNAPSHOTS.clear();
	}

	/** A required string of at most maxBytes UTF-8 bytes. */
	public static String string(final JsonObject json, final String key, final int maxBytes) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(key + " must be a string");
		}

		String s = e.getAsString();
		if (s.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			throw new IllegalArgumentException(key + " is longer than " + maxBytes + " bytes");
		}

		return s;
	}

	/** A required "#RRGGBB" colour as 0xRRGGBB. */
	public static int colour(final JsonObject json, final String key) {
		String s = string(json, key, 7);
		if (!COLOUR.matcher(s).matches()) {
			throw new IllegalArgumentException(key + " must be #RRGGBB");
		}

		return Integer.parseInt(s.substring(1), 16);
	}

	/** A required Minecraft position [x, y, z] (finite, |x|, |z| within the world border, |y| within 20000). */
	public static double[] position(final JsonObject json, final String key) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			throw new IllegalArgumentException(key + " must be [x, y, z]");
		}

		double[] p = new double[3];
		for (int i = 0; i < 3; i++) {
			JsonElement v = e.getAsJsonArray().get(i);
			p[i] = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
			if (!(Math.abs(p[i]) <= (i == 1 ? 20_000.0 : 2.9e7))) {
				throw new IllegalArgumentException(key + " must hold three finite numbers within the world");
			}
		}

		return p;
	}
}
