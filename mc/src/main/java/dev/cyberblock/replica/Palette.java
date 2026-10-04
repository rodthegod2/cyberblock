package dev.cyberblock.replica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Ranges;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Which replica block a Night City cell becomes (megaplan task 3-R1, content kind {@code material_palette} of section 1.7).
 *
 * <p>A probe code (ground.m, 1..26, the shared material table) goes through the palette to a block; without one (no m, or 0: Phase 0
 * b unconfirmed) the heuristic classifies the cell from its shape (a thin floor or a tall wall), its height against the host's Steve
 * and the shared map's surface, the map's water flag and the host's district, and the palette's heuristic entries give that class a
 * block. The built-in table below makes either path work with no content at all; content entries only override it.
 *
 * <p>A {@code material_palette} file (unknown keys are left to other readers, such as 3A's colours and material names):
 * {@code {"schema":1, "block":"cyberblock:nc_<material>", "code"?: 1..26, "heuristic"?: "<class>", "colour"?: "#RRGGBB"}}: with
 * {@code code} it maps that probe code to the block; with {@code heuristic} that class (lower case, {@link Heuristic}); with neither
 * the block's own code to itself.
 */
public final class Palette {
	/** The heuristic classes, each with its default material. */
	public enum Heuristic {
		STREET(ReplicaBlocks.ASPHALT), SIDEWALK(ReplicaBlocks.SIDEWALK), ROOF(ReplicaBlocks.CONCRETE_LIGHT), OFFROAD(ReplicaBlocks.GROUND),
		WALL_BASE(ReplicaBlocks.CONCRETE_DARK), WALL_BRICK(ReplicaBlocks.BRICK), WALL_TRIM(ReplicaBlocks.METAL), WALL_WINDOW(ReplicaBlocks.GLASS),
		WALL(ReplicaBlocks.CONCRETE_LIGHT), WATER(ReplicaBlocks.WATER);

		final int material;

		Heuristic(final int material) {
			this.material = material;
		}

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** One palette file: code (or -1) or heuristic (or null) to a material. */
	public record Entry(int code, @Nullable Heuristic heuristic, int material) {
	}

	/** A range at most this high is a floor (a street, a roof); a higher one a wall. */
	static final int FLOOR_HEIGHT = 2;
	/** The bottom cells of a wall: its base. */
	static final int WALL_BASE = 3;
	private static final String[] BRICK_DISTRICTS = {"watson", "kabuki", "little china", "northside", "santo domingo", "arroyo", "rancho coronado"};
	private static final String[] OFFROAD_DISTRICTS = {"badlands", "rocky ridge", "red peaks", "jackson plains", "laguna bend", "biotechnica flats",
		"north sunrise", "sierra sonora", "vasquez", "dam"};
	private static @Nullable ContentKind<Entry> kind;
	/** The tables last built and the content snapshot they were built from (content is replaced whole on /reload). */
	private static volatile Tables tables = Tables.build(Map.of());

	private record Tables(Map<Identifier, Entry> from, int[] probe, int[] heuristic) {
		static Tables build(final Map<Identifier, Entry> entries) {
			int[] probe = new int[ReplicaBlocks.CODES];
			for (int c = 0; c < probe.length; c++) {
				probe[c] = c;
			}

			int[] heuristic = new int[Heuristic.values().length];
			for (Heuristic h : Heuristic.values()) {
				heuristic[h.ordinal()] = h.material;
			}

			for (Entry e : entries.values()) {
				if (e.heuristic() != null) {
					heuristic[e.heuristic().ordinal()] = e.material();
				} else {
					probe[e.code() > 0 ? e.code() : e.material()] = e.material();
				}
			}

			return new Tables(entries, probe, heuristic);
		}
	}

	private Palette() {
	}

	/** Common init, once (before the server loads its datapacks). Clients get it too (for map colours). */
	static void register() {
		kind = ContentKind.register("material_palette", Palette::parse, true);
	}

	static Entry parse(final Identifier id, final JsonObject json) {
		int material = ReplicaBlocks.code(Content.string(json, "block", 64));
		if (material == ReplicaBlocks.UNKNOWN) {
			throw new IllegalArgumentException("block must be a cyberblock:nc_* replica block");
		}

		if (json.has("colour")) {
			Content.colour(json, "colour");
		}

		Heuristic heuristic = null;
		if (json.has("heuristic")) {
			String h = Content.string(json, "heuristic", 32);
			for (Heuristic c : Heuristic.values()) {
				heuristic = c.id().equals(h) ? c : heuristic;
			}

			if (heuristic == null) {
				throw new IllegalArgumentException("heuristic must be one of street, sidewalk, roof, offroad, wall_base, wall_brick, wall_trim, wall_window, wall, water");
			}
		}

		int code = -1;
		if (json.has("code")) {
			JsonElement e = json.get("code");
			double v = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
			if (!(v >= 1 && v < ReplicaBlocks.CODES) || v != Math.rint(v)) {
				throw new IllegalArgumentException("code must be a whole number from 1 to " + (ReplicaBlocks.CODES - 1));
			}

			code = (int) v;
		}

		if (code > 0 && heuristic != null) {
			throw new IllegalArgumentException("an entry has a code or a heuristic, not both");
		}

		return new Entry(code, heuristic, material);
	}

	private static Tables tables() {
		Tables t = tables;
		if (kind == null) {
			return t; // before register (tests)
		}

		Map<Identifier, Entry> now = Content.all(kind);
		if (t.from() != now) {
			t = Tables.build(now);
			tables = t;
		}

		return t;
	}

	/** The material for a probe code through the palette; 0 when the code is unknown (the heuristic decides then). */
	public static int fromProbe(final int code) {
		return code > ReplicaBlocks.UNKNOWN && code < ReplicaBlocks.CODES ? tables().probe()[code] : ReplicaBlocks.UNKNOWN;
	}

	/** The material of a heuristic class through the palette. */
	public static int fromHeuristic(final Heuristic h) {
		return tables().heuristic()[h.ordinal()];
	}

	/** Tests (before register): the tables from these entries instead of the loaded content. */
	static void use(final Map<Identifier, Entry> entries) {
		tables = Tables.build(entries);
	}

	/**
	 * What a cell of a ground range is, from what the server knows without a probe material. y is the cell's, r its range; refY the
	 * host's Steve's feet (the street he stands on is refY - 1); mapTop the shared map's highest surface over the cell
	 * (Integer.MIN_VALUE when unknown) and water its water flag; district the host's HUD district ("" when unknown).
	 */
	public static Heuristic classify(final Ranges.Range r, final int y, final int refY, final int mapTop, final boolean water, final String district) {
		boolean mapped = mapTop != Integer.MIN_VALUE;
		if (water && mapped && y >= mapTop - 1) {
			return Heuristic.WATER;
		}

		String d = district.toLowerCase(Locale.ROOT);
		if (r.height() <= FLOOR_HEIGHT) {
			int street = refY - 1;
			if (y <= street + 1) {
				return contains(d, OFFROAD_DISTRICTS) ? Heuristic.OFFROAD : Heuristic.STREET;
			}

			return y <= street + 3 && !(mapped && y >= mapTop - 1) ? Heuristic.SIDEWALK : Heuristic.ROOF;
		}

		int up = y - r.yBottom();
		if (y == r.yTop() && up >= WALL_BASE) {
			return Heuristic.ROOF;
		}

		if (up < WALL_BASE) {
			return contains(d, BRICK_DISTRICTS) ? Heuristic.WALL_BRICK : Heuristic.WALL_BASE;
		}

		return switch (Math.floorMod(up, 4)) {
			case 0 -> Heuristic.WALL_TRIM;
			case 1, 2 -> Math.floorMod(r.x() + r.z(), 3) != 0 ? Heuristic.WALL_WINDOW : Heuristic.WALL;
			default -> Heuristic.WALL;
		};
	}

	private static boolean contains(final String district, final String[] names) {
		for (String n : names) {
			if (district.contains(n)) {
				return true;
			}
		}

		return false;
	}

	/** The material of one cell: the probe code through the palette, else the heuristic class through the palette. */
	public static int material(final int probeCode, final Ranges.Range r, final int y, final int refY, final int mapTop, final boolean water,
		final String district) {
		int m = fromProbe(probeCode);
		return m != ReplicaBlocks.UNKNOWN ? m : fromHeuristic(classify(r, y, refY, mapTop, water, district));
	}
}
