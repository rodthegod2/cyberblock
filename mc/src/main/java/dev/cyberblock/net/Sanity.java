package dev.cyberblock.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.cyberblock.Ranges;

/**
 * The sanity radius of megaplan section 1.6: every position of an UP message must lie within the radius (horizontally) of the
 * host's player; an entry outside it is dropped, and a message left with nothing to say is dropped whole. Positions are read in the
 * shapes of shared/protocol.md (ground and unground quadruples through the existing Ranges parser). Pure; edits the message in place.
 */
final class Sanity {
	/** Survey stops cover 220 m squares, so terrain gets a wider radius than the configured one. */
	static final double TERRAIN_RADIUS = 400.0;

	/** Which positions a type carries. */
	enum Kind {
		NONE,
		/** c:[x, z, yBottom, yTop, ...] (ground, unground). */
		RANGES,
		/** c:[cx, cz, y, s, ...], the 2 m cell cx*2, cz*2 (terrain; the wide radius). */
		TERRAIN,
		/** c:[x, y, z, ...] (pcells). */
		CELLS,
		/** list:[{p:[x, y, z]}, ...] (npcs). */
		LIST,
		/** from:[x, y, z] or null (dmg): a far one becomes null, the damage stays. */
		FROM,
		/** p:[x, y, z], optional (scaninfo): a far one drops the message. */
		POINT
	}

	/** keep false: drop the whole message. far: entries dropped for distance. */
	record Result(boolean keep, int far) {
		static final Result KEEP = new Result(true, 0);
	}

	private Sanity() {
	}

	private static boolean near(final double x, final double z, final double px, final double pz, final double radius) {
		double dx = x - px, dz = z - pz;
		return dx * dx + dz * dz <= radius * radius;
	}

	private static int[] ints(final JsonObject m, final String key) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonArray()) {
			throw new IllegalArgumentException(key + " must be an array");
		}

		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			JsonElement v = a.get(i);
			double d = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
			if (!(Math.abs(d) <= 3.0e7) || d != Math.rint(d)) {
				throw new IllegalArgumentException(key + "[" + i + "] must be a whole number");
			}

			out[i] = (int) d;
		}

		return out;
	}

	private static double[] point(final JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			throw new IllegalArgumentException("a position must be [x, y, z]");
		}

		double[] p = new double[3];
		for (int i = 0; i < 3; i++) {
			JsonElement v = e.getAsJsonArray().get(i);
			p[i] = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
			if (!Double.isFinite(p[i])) {
				throw new IllegalArgumentException("a position must be finite");
			}
		}

		return p;
	}

	private static JsonArray array(final int[] values, final int from, final int to, final JsonArray into) {
		for (int i = from; i < to; i++) {
			into.add(values[i]);
		}

		return into;
	}

	/**
	 * Applies the radius to the message (in place). Throws IllegalArgumentException when its positions are malformed (the caller drops
	 * it as invalid).
	 */
	static Result filter(final Kind kind, final JsonObject m, final double px, final double pz, final double radius) {
		return switch (kind) {
			case NONE -> Result.KEEP;
			case RANGES -> {
				int[] c = ints(m, "c");
				Ranges.decode(c); // the existing parser's checks: whole quadruples, not inverted
				yield groups(m, c, 4, (i) -> near(c[i] + 0.5, c[i + 1] + 0.5, px, pz, radius));
			}
			case TERRAIN -> {
				int[] c = ints(m, "c");
				if (c.length % 4 != 0) {
					throw new IllegalArgumentException("terrain needs quadruples");
				}

				yield groups(m, c, 4, (i) -> near(c[i] * 2.0 + 1.0, c[i + 1] * 2.0 + 1.0, px, pz, TERRAIN_RADIUS));
			}
			case CELLS -> {
				int[] c = ints(m, "c");
				if (c.length % 3 != 0) {
					throw new IllegalArgumentException("cells need triples");
				}

				yield groups(m, c, 3, (i) -> near(c[i] + 0.5, c[i + 2] + 0.5, px, pz, radius));
			}
			case LIST -> {
				JsonElement e = m.get("list");
				if (e == null || !e.isJsonArray()) {
					throw new IllegalArgumentException("list must be an array");
				}

				JsonArray in = e.getAsJsonArray(), out = new JsonArray();
				for (JsonElement entry : in) {
					if (!entry.isJsonObject()) {
						throw new IllegalArgumentException("list entries must be objects");
					}

					double[] p = point(entry.getAsJsonObject().get("p"));
					if (near(p[0], p[2], px, pz, radius)) {
						out.add(entry);
					}
				}

				int far = in.size() - out.size();
				if (far > 0) {
					m.add("list", out);
				}

				// an empty list means "everything is gone": only send it when the host really sent one
				yield new Result(in.isEmpty() || !out.isEmpty(), far);
			}
			case FROM -> {
				JsonElement e = m.get("from");
				if (e == null || e.isJsonNull()) {
					yield Result.KEEP;
				}

				double[] p = point(e);
				if (near(p[0], p[2], px, pz, radius)) {
					yield Result.KEEP;
				}

				m.add("from", JsonNull.INSTANCE);
				yield new Result(true, 1);
			}
			case POINT -> {
				JsonElement e = m.get("p");
				if (e == null || e.isJsonNull()) {
					yield Result.KEEP;
				}

				double[] p = point(e);
				yield near(p[0], p[2], px, pz, radius) ? Result.KEEP : new Result(false, 1);
			}
		};
	}

	private interface Near {
		boolean test(int index);
	}

	/** Keeps the groups (of size n) whose first index passes; a message whose every group was far is dropped. */
	private static Result groups(final JsonObject m, final int[] c, final int n, final Near near) {
		JsonArray out = new JsonArray();
		int far = 0;
		for (int i = 0; i + n <= c.length; i += n) {
			if (near.test(i)) {
				array(c, i, i + n, out);
			} else {
				far++;
			}
		}

		if (far == 0) {
			return Result.KEEP;
		}

		m.add("c", out);
		return new Result(!out.isEmpty(), far);
	}
}
