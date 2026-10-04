package dev.cyberblock.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Afterlife's layout (megaplan 7-H1), the {@code hub} content file that 7A's art/gen_hub.py writes
 * ({@code shared/content-schemas/hub.schema.json}): {@code origin} is the world cell of the layout cell (0, 0, 0) (x east, z south, y
 * up; the floor is layout y 0), {@code steps} fill boxes in order ({@code mode} {@code hollow}: only the shell), {@code spawn} is a feet
 * position and yaw, {@code stations} are the Match Terminal, Holo-Table and exit portal cells, {@code boards} the leaderboard boards
 * (facing, scoreboard objective, title key), {@code areas} named boxes and {@code figures} the hub NPCs (7N places them; only counted
 * here). Every position is a layout cell; {@link #world} turns one into a world cell. Pure: a bad file throws
 * IllegalArgumentException with the reason, and the builder falls back to {@link #fallback}.
 */
public record HubLayout(String titleKey, Cell origin, Cell size, double[] spawn, float spawnYaw, Map<String, Box> areas, List<Step> steps,
	Cell matchTerminal, Cell holoTable, Cell exitPortal, List<Board> boards, int figures) {

	/** A layout cell (or, after {@link #world}, a world cell). */
	public record Cell(int x, int y, int z) {
		Cell plus(final Cell o) {
			return new Cell(this.x + o.x, this.y + o.y, this.z + o.z);
		}
	}

	/** A box of cells, both corners included, in any order. */
	public record Box(Cell from, Cell to) {
		public int minX() {
			return Math.min(this.from.x(), this.to.x());
		}

		public int minY() {
			return Math.min(this.from.y(), this.to.y());
		}

		public int minZ() {
			return Math.min(this.from.z(), this.to.z());
		}

		public int maxX() {
			return Math.max(this.from.x(), this.to.x());
		}

		public int maxY() {
			return Math.max(this.from.y(), this.to.y());
		}

		public int maxZ() {
			return Math.max(this.from.z(), this.to.z());
		}

		public long volume() {
			return (long) (this.maxX() - this.minX() + 1) * (this.maxY() - this.minY() + 1) * (this.maxZ() - this.minZ() + 1);
		}

		/** Is the cell on the box's outer shell (a hollow step fills only these)? */
		public boolean shell(final int x, final int y, final int z) {
			return x == this.minX() || x == this.maxX() || y == this.minY() || y == this.maxY() || z == this.minZ() || z == this.maxZ();
		}
	}

	/** Fill the box with the block (a block id with optional state; {@code minecraft:air} carves), whole or only its shell. */
	public record Step(Box box, String block, boolean hollow) {
	}

	/** A leaderboard board: its cell, the way its face looks, the scoreboard objective it shows and its title's lang key. */
	public record Board(Cell pos, String facing, String objective, String titleKey) {
	}

	static final int MAX_COORD = 512, MAX_SIZE = 256, MAX_STEPS = 4000, MAX_BOARDS = 16, MAX_FIGURES = 32;
	/** Cells the steps may fill together: a hub is tens of thousands; this keeps a bad file from stalling the server start. */
	static final long MAX_CELLS = 4_000_000L;
	private static final Pattern BLOCK = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?");
	private static final Pattern KEY = Pattern.compile("[a-z0-9_.]+");
	private static final Pattern OBJECTIVE = Pattern.compile("[a-z0-9_]{1,16}");

	/** A world cell: the layout cell moved by the origin. */
	public Cell world(final Cell layoutCell) {
		return this.origin.plus(layoutCell);
	}

	/** The world feet position of the spawn. */
	public double[] spawnWorld() {
		return new double[] {this.origin.x() + this.spawn[0], this.origin.y() + this.spawn[1], this.origin.z() + this.spawn[2]};
	}

	/** Below this world y a player has fallen off the hub: the floor's world y minus 16. */
	public int voidY() {
		return this.origin.y() - 16;
	}

	/** A layout file (schema 1, shared/content-schemas/hub.schema.json). */
	public static HubLayout parse(final JsonObject json) {
		if (!(json.get("schema") instanceof JsonPrimitive s) || !s.isNumber() || s.getAsDouble() != 1.0) {
			throw new IllegalArgumentException("schema must be 1");
		}

		String title = text(json, "title_key", 96, KEY);
		Cell origin = cell(json.get("origin"), MAX_COORD, "origin");
		Cell size = cell(json.get("size"), MAX_SIZE, "size");
		if (size.x() < 1 || size.y() < 1 || size.z() < 1) {
			throw new IllegalArgumentException("size must be at least 1 in every direction");
		}

		JsonObject spawnJson = object(json.get("spawn"), "spawn");
		double[] spawn = numbers(spawnJson.get("pos"), "spawn.pos");
		float yaw = (float) number(spawnJson.get("yaw"), 0, 360, "spawn.yaw");
		Map<String, Box> areas = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : object(json.get("areas"), "areas").entrySet()) {
			if (!e.getKey().matches("[a-z0-9_]{1,32}")) {
				throw new IllegalArgumentException("area names are a-z, 0-9 and _ (at most 32)");
			}

			areas.put(e.getKey(), box(object(e.getValue(), "an area"), "area " + e.getKey()));
		}

		List<Step> steps = steps(json.get("steps"));
		JsonObject stations = object(json.get("stations"), "stations");
		Cell terminal = cell(stations.get("match_terminal"), MAX_COORD, "stations.match_terminal");
		Cell holo = cell(stations.get("holo_table"), MAX_COORD, "stations.holo_table");
		Cell portal = cell(stations.get("exit_portal"), MAX_COORD, "stations.exit_portal");
		List<Board> boards = boards(json.get("boards"));
		if (!(json.get("figures") instanceof JsonArray figures) || figures.size() > MAX_FIGURES) {
			throw new IllegalArgumentException("figures must be an array of at most " + MAX_FIGURES);
		}

		return new HubLayout(title, origin, size, spawn, yaw, Map.copyOf(areas), steps, terminal, holo, portal, boards, figures.size());
	}

	private static List<Step> steps(final @Nullable JsonElement e) {
		if (!(e instanceof JsonArray a) || a.isEmpty() || a.size() > MAX_STEPS) {
			throw new IllegalArgumentException("steps must be an array of 1 to " + MAX_STEPS);
		}

		List<Step> out = new ArrayList<>(a.size());
		long cells = 0;
		for (JsonElement s : a) {
			JsonObject o = object(s, "a step");
			Box box = box(o, "a step");
			String block = text(o, "block", 160, BLOCK);
			boolean hollow = false;
			if (o.has("mode")) {
				String mode = text(o, "mode", 8, Pattern.compile("fill|hollow"));
				hollow = mode.equals("hollow");
			}

			cells += box.volume();
			if (cells > MAX_CELLS) {
				throw new IllegalArgumentException("the steps fill more than " + MAX_CELLS + " cells");
			}

			out.add(new Step(box, block, hollow));
		}

		return List.copyOf(out);
	}

	private static List<Board> boards(final @Nullable JsonElement e) {
		if (!(e instanceof JsonArray a) || a.size() > MAX_BOARDS) {
			throw new IllegalArgumentException("boards must be an array of at most " + MAX_BOARDS);
		}

		List<Board> out = new ArrayList<>();
		for (JsonElement b : a) {
			JsonObject o = object(b, "a board");
			String facing = text(o, "facing", 5, Pattern.compile("north|east|south|west"));
			out.add(new Board(cell(o.get("pos"), MAX_COORD, "board pos"), facing, text(o, "objective", 16, OBJECTIVE), text(o, "title_key", 96, KEY)));
		}

		return List.copyOf(out);
	}

	/**
	 * The built-in hub when the layout file is missing or bad: a lit 15 x 15 floor at world (0, 64, 0) with the exit portal, the Match
	 * Terminal, a Holo-Table spot and one leaderboard, so the hub always works (the log line says which one was built).
	 */
	public static HubLayout fallback() {
		Box floor = new Box(new Cell(-7, 0, -7), new Cell(7, 0, 7));
		List<Step> steps = List.of(
			new Step(floor, "minecraft:polished_blackstone_bricks", false),
			new Step(new Box(new Cell(-7, 0, -7), new Cell(7, 0, -7)), "minecraft:sea_lantern", false),
			new Step(new Box(new Cell(-7, 0, 7), new Cell(7, 0, 7)), "minecraft:sea_lantern", false));
		return new HubLayout("cyberblock.hub.title", new Cell(0, 64, 0), new Cell(15, 6, 15), new double[] {0.5, 1.0, 0.5}, 180.0F,
			Map.of("hall", floor), steps, new Cell(-3, 1, -5), new Cell(3, 1, -5), new Cell(0, 1, 5),
			List.of(new Board(new Cell(0, 2, -6), "south", Leaderboards.MATCH_WINS, "cyberblock.hub.board.match_wins")), 0);
	}

	private static JsonObject object(final @Nullable JsonElement e, final String what) {
		if (!(e instanceof JsonObject o)) {
			throw new IllegalArgumentException(what + " must be an object");
		}

		return o;
	}

	private static Box box(final JsonObject o, final String what) {
		return new Box(cell(o.get("from"), MAX_COORD, what + " from"), cell(o.get("to"), MAX_COORD, what + " to"));
	}

	private static Cell cell(final @Nullable JsonElement e, final int max, final String what) {
		if (!(e instanceof JsonArray a) || a.size() != 3) {
			throw new IllegalArgumentException(what + " must be [x, y, z]");
		}

		int[] v = new int[3];
		for (int i = 0; i < 3; i++) {
			double d = a.get(i) instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
			if (!(d >= -max && d <= max) || d != Math.rint(d)) {
				throw new IllegalArgumentException(what + " must be whole numbers from -" + max + " to " + max);
			}

			v[i] = (int) d;
		}

		return new Cell(v[0], v[1], v[2]);
	}

	private static double[] numbers(final @Nullable JsonElement e, final String what) {
		if (!(e instanceof JsonArray a) || a.size() != 3) {
			throw new IllegalArgumentException(what + " must be [x, y, z]");
		}

		double[] v = new double[3];
		for (int i = 0; i < 3; i++) {
			v[i] = number(a.get(i), -MAX_COORD, MAX_COORD, what);
		}

		return v;
	}

	private static double number(final @Nullable JsonElement e, final double min, final double max, final String what) {
		double d = e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
		if (!(d >= min && d <= max)) {
			throw new IllegalArgumentException(what + " must be a number from " + min + " to " + max);
		}

		return d;
	}

	private static String text(final JsonObject o, final String key, final int maxBytes, final Pattern pattern) {
		JsonElement e = o.get(key);
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			throw new IllegalArgumentException(key + " must be a string");
		}

		String s = p.getAsString();
		if (s.getBytes(StandardCharsets.UTF_8).length > maxBytes || !pattern.matcher(s).matches()) {
			throw new IllegalArgumentException(key + " is not a valid value: " + s);
		}

		return s;
	}
}
