package dev.cyberblock.holo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@code landmark} content kind (megaplan section 1.7: district landmarks and spawn points; defaults by 7A in
 * {@code data/cyberblock/cyberblock/landmark/}): {@code {"schema": 1, "name": str <= 64, "pos": [x, y, z], "district"?: str <= 64,
 * "kind"?: str <= 32 (default "landmark"), "yaw"?: number, "spawn"?: bool (default true)}}; unknown fields are ignored. The
 * Holo-Table offers every entry with {@code spawn} as a spawn point; 7N's gigs may read the same entries ("reach a landmark").
 *
 * <p>The kind is registered once: if another package registered {@code landmark} first (7N reads landmarks too), this class reads
 * that package's entries by their record fields ({@code name}, {@code pos} as {@code double[]} or {@code Vec3}, and the optional
 * {@code district}, {@code kind}, {@code yaw}, {@code spawn}) and says so in the log, so the two never collide at startup.
 */
public final class Landmarks {
	public static final String KIND = "landmark";

	public record Landmark(String name, Vec3 pos, String district, String kind, float yaw, boolean spawn) {
	}

	private static @Nullable ContentKind<?> kind;

	private Landmarks() {
	}

	static void register() {
		ContentKind<?> existing = ContentKind.byId(KIND);
		if (existing != null) {
			kind = existing;
			Cyberblock.LOG.info("holo: the landmark content kind is registered by another package: the Holo-Table reads its entries by field");
			return;
		}

		kind = ContentKind.register(KIND, Landmarks::parse, false);
	}

	static Landmark parse(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "name", 64);
		double[] p = Content.position(json, "pos");
		String district = json.has("district") ? Content.string(json, "district", 64) : "";
		String kind = json.has("kind") ? Content.string(json, "kind", 32) : "landmark";
		float yaw = 0.0F;
		JsonElement y = json.get("yaw");
		if (y != null) {
			if (!y.isJsonPrimitive() || !y.getAsJsonPrimitive().isNumber() || !Double.isFinite(y.getAsDouble())) {
				throw new IllegalArgumentException("yaw must be a number");
			}

			yaw = (float) y.getAsDouble();
		}

		boolean spawn = true;
		JsonElement s = json.get("spawn");
		if (s != null) {
			if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isBoolean()) {
				throw new IllegalArgumentException("spawn must be true or false");
			}

			spawn = s.getAsBoolean();
		}

		return new Landmark(name, new Vec3(p[0], p[1], p[2]), district, kind, yaw, spawn);
	}

	/** Every loaded landmark by id (sorted), whoever registered the kind. */
	static List<Map.Entry<Identifier, Landmark>> all() {
		ContentKind<?> k = kind;
		List<Map.Entry<Identifier, Landmark>> out = new ArrayList<>();
		if (k == null) {
			return out;
		}

		for (Map.Entry<Identifier, ?> e : Content.all(k).entrySet()) {
			Landmark l = adapt(e.getValue());
			if (l != null) {
				out.add(Map.entry(e.getKey(), l));
			}
		}

		return out;
	}

	/** Our record as it is, or another package's record read by its component names; null when it has no name or position. */
	static @Nullable Landmark adapt(final @Nullable Object value) {
		if (value instanceof Landmark l) {
			return l;
		}

		if (value == null || !value.getClass().isRecord()) {
			return null;
		}

		try {
			String name = null, district = "", kind = "landmark";
			Vec3 pos = null;
			float yaw = 0.0F;
			boolean spawn = true;
			for (RecordComponent c : value.getClass().getRecordComponents()) {
				java.lang.reflect.Method accessor = c.getAccessor();
				accessor.trySetAccessible();
				Object v = accessor.invoke(value);
				switch (c.getName()) {
					case "name" -> name = v instanceof String s ? s : null;
					case "district" -> district = v instanceof String s ? s : "";
					case "kind" -> kind = v instanceof String s ? s : "landmark";
					case "yaw" -> yaw = v instanceof Number n ? n.floatValue() : 0.0F;
					case "spawn" -> spawn = !(v instanceof Boolean b) || b;
					case "pos" -> pos = v instanceof Vec3 vec ? vec : v instanceof double[] d && d.length == 3 ? new Vec3(d[0], d[1], d[2]) : null;
					default -> {
					}
				}
			}

			return name == null || pos == null ? null : new Landmark(name, pos, district, kind, yaw, spawn);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
