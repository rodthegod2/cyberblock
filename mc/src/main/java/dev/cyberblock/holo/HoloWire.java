package dev.cyberblock.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Holo-Table's messages (megaplan section 1.3 {@code cyberblock:act} / {@code cyberblock:ui}, task 7-T1), built on the server
 * and read on the client (pure, tested; both sides share this class like ShopWire).
 *
 * <p>Client to server ({@code cyberblock:act}):
 * <ul>
 * <li>{@code holo} {@code {"detail": bool}}: while the table view is open, once a second: "send me the table" (with the replica
 * detail around the players when {@code detail}).</li>
 * <li>{@code holospawn} {@code {"kind": "landmark"|"beacon"|"base"|"friend", "id": str <= 64}}: spawn there.</li>
 * <li>{@code holohide} {@code {"on": bool}}: hide my pin (outside matches).</li>
 * </ul>
 * Server to client ({@code cyberblock:ui t:"holo"}): {@code {"open": bool, "match": bool, "role": "player"|"spectator"|"overlord",
 * "hidden": bool, "cooldown": s, "pins": [...], "spawns": [...], "layers": [...], "detail"?: [...]}} where
 * <ul>
 * <li>a pin is {@code {"id": uuid, "name", "p": [x, y, z], "yaw", "linked", "team" (-1 none), "col" ("#RRGGBB" or absent), "car",
 * "combat", "downed", "you"}} (only the pins this player may see: the server filters, the client never gets a hidden pin);</li>
 * <li>a spawn is {@code {"kind", "id", "name", "p": [x, y, z], "district", "owner", "ok", "why"}} ({@code why} the lang key of the
 * refusal when not {@code ok});</li>
 * <li>a layer is {@code {"id", "items": [{"p": [x, z], "v": 0..1, "col"?, "label"?}]}};</li>
 * <li>a detail patch is {@code {"x0", "z0", "n", "h": base64 of n*n big-endian shorts (surface y, -32768 unknown), "c": base64 of
 * n*n map colour ids}}, x-major from the north-west corner.</li>
 * </ul>
 */
public final class HoloWire {
	public static final String T_STATE = "holo";
	public static final String T_POLL = "holo";
	public static final String T_SPAWN = "holospawn";
	public static final String T_HIDE = "holohide";
	public static final int MAX_PINS = 200, MAX_SPAWNS = 160, MAX_LAYERS = 16, MAX_ITEMS = 512, MAX_DETAIL = 16, MAX_DETAIL_N = 65;
	public static final int NAME_MAX = 64, ID_MAX = 64;
	public static final short UNKNOWN = Short.MIN_VALUE;
	private static final double MAX_COORD = 3.0E7;
	private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
	private static final Pattern LAYER_ID = Pattern.compile("[a-z0-9_]{1,24}");

	public record Pin(UUID id, String name, double x, double y, double z, float yaw, boolean linked, int team, int colour, boolean car, boolean combat,
		boolean downed, boolean you) {
	}

	public record SpawnPoint(String kind, String id, String name, double x, double y, double z, String district, String owner, boolean ok, String why) {
	}

	public record LayerItem(double x, double z, float v, int colour, String label) {
	}

	public record Layer(String id, List<LayerItem> items) {
	}

	/** Surface heights ({@link #UNKNOWN} where none) and map colour ids of an n x n square of block columns from (x0, z0). */
	public record Detail(int x0, int z0, int n, short[] heights, byte[] colours) {
		public boolean covers(final int x, final int z) {
			return x >= this.x0 && z >= this.z0 && x < this.x0 + this.n && z < this.z0 + this.n;
		}

		public int index(final int x, final int z) {
			return (z - this.z0) * this.n + (x - this.x0);
		}
	}

	/** The table as one viewer may see it; detail is null when this answer carries none (the client keeps what it has). */
	public record State(boolean open, boolean match, String role, boolean hidden, int cooldown, List<Pin> pins, List<SpawnPoint> spawns, List<Layer> layers,
		@Nullable List<Detail> detail) {
	}

	private HoloWire() {
	}

	// ---- client to server ----

	public static String poll(final boolean detail) {
		JsonObject o = new JsonObject();
		o.addProperty("detail", detail);
		return o.toString();
	}

	public static boolean pollDetail(final JsonObject m) {
		return m.get("detail") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
	}

	public static String spawn(final HoloRules.Kind kind, final String id) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind.key());
		o.addProperty("id", id);
		return o.toString();
	}

	/** A spawn request: {kind, id} of a known kind and an id of at most 64 bytes, else null. */
	public static @Nullable SpawnAsk parseSpawn(final JsonObject m) {
		String kind = string(m, "kind", 16), id = string(m, "id", ID_MAX);
		HoloRules.Kind k = kind == null ? null : HoloRules.Kind.of(kind);
		return k == null || id == null || id.isEmpty() ? null : new SpawnAsk(k, id);
	}

	public record SpawnAsk(HoloRules.Kind kind, String id) {
	}

	public static String hide(final boolean on) {
		JsonObject o = new JsonObject();
		o.addProperty("on", on);
		return o.toString();
	}

	/** holohide's flag, or null when the message has none. */
	public static @Nullable Boolean parseHide(final JsonObject m) {
		return m.get("on") instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
	}

	// ---- server to client ----

	public static String state(final State s) {
		JsonObject o = new JsonObject();
		o.addProperty("open", s.open());
		o.addProperty("match", s.match());
		o.addProperty("role", s.role());
		o.addProperty("hidden", s.hidden());
		o.addProperty("cooldown", s.cooldown());
		JsonArray pins = new JsonArray();
		for (Pin p : s.pins().subList(0, Math.min(MAX_PINS, s.pins().size()))) {
			JsonObject j = new JsonObject();
			j.addProperty("id", p.id().toString());
			j.addProperty("name", cap(p.name(), NAME_MAX));
			j.add("p", xyz(p.x(), p.y(), p.z()));
			j.addProperty("yaw", Math.round(p.yaw()));
			j.addProperty("linked", p.linked());
			j.addProperty("team", p.team());
			if (p.colour() >= 0) {
				j.addProperty("col", colour(p.colour()));
			}

			j.addProperty("car", p.car());
			j.addProperty("combat", p.combat());
			j.addProperty("downed", p.downed());
			j.addProperty("you", p.you());
			pins.add(j);
		}

		o.add("pins", pins);
		JsonArray spawns = new JsonArray();
		for (SpawnPoint p : s.spawns().subList(0, Math.min(MAX_SPAWNS, s.spawns().size()))) {
			JsonObject j = new JsonObject();
			j.addProperty("kind", p.kind());
			j.addProperty("id", cap(p.id(), ID_MAX));
			j.addProperty("name", cap(p.name(), NAME_MAX));
			j.add("p", xyz(p.x(), p.y(), p.z()));
			j.addProperty("district", cap(p.district(), NAME_MAX));
			j.addProperty("owner", cap(p.owner(), NAME_MAX));
			j.addProperty("ok", p.ok());
			j.addProperty("why", p.why());
			spawns.add(j);
		}

		o.add("spawns", spawns);
		JsonArray layers = new JsonArray();
		for (Layer l : s.layers().subList(0, Math.min(MAX_LAYERS, s.layers().size()))) {
			JsonObject j = new JsonObject();
			j.addProperty("id", l.id());
			JsonArray items = new JsonArray();
			for (LayerItem i : l.items().subList(0, Math.min(MAX_ITEMS, l.items().size()))) {
				JsonObject k = new JsonObject();
				JsonArray p = new JsonArray();
				p.add(round(i.x()));
				p.add(round(i.z()));
				k.add("p", p);
				k.addProperty("v", Math.round(Math.clamp(i.v(), 0.0F, 1.0F) * 100.0F) / 100.0);
				if (i.colour() >= 0) {
					k.addProperty("col", colour(i.colour()));
				}

				if (!i.label().isEmpty()) {
					k.addProperty("label", cap(i.label(), NAME_MAX));
				}

				items.add(k);
			}

			j.add("items", items);
			layers.add(j);
		}

		o.add("layers", layers);
		if (s.detail() != null) {
			JsonArray detail = new JsonArray();
			for (Detail d : s.detail().subList(0, Math.min(MAX_DETAIL, s.detail().size()))) {
				JsonObject j = new JsonObject();
				j.addProperty("x0", d.x0());
				j.addProperty("z0", d.z0());
				j.addProperty("n", d.n());
				ByteBuffer b = ByteBuffer.allocate(d.heights().length * 2);
				b.asShortBuffer().put(d.heights());
				j.addProperty("h", Base64.getEncoder().encodeToString(b.array()));
				j.addProperty("c", Base64.getEncoder().encodeToString(d.colours()));
				detail.add(j);
			}

			o.add("detail", detail);
		}

		return o.toString();
	}

	/** The table from the server, or null when the message is not a valid one (a bad entry drops the whole message). */
	public static @Nullable State parseState(final JsonObject o) {
		try {
			return parse(o);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static State parse(final JsonObject o) {
		String role = need(string(o, "role", 16));
		List<Pin> pins = new ArrayList<>();
		for (JsonElement e : array(o, "pins", MAX_PINS)) {
			JsonObject j = object(e);
			UUID id;
			try {
				id = UUID.fromString(need(string(j, "id", 36)));
			} catch (IllegalArgumentException x) {
				throw new IllegalArgumentException("pin id");
			}

			double[] p = position(j.get("p"), 3);
			pins.add(new Pin(id, need(string(j, "name", NAME_MAX)), p[0], p[1], p[2], (float) number(j, "yaw", -720, 720), bool(j, "linked"),
				(int) number(j, "team", -1, 1 << 20), optColour(j), bool(j, "car"), bool(j, "combat"), bool(j, "downed"), bool(j, "you")));
		}

		List<SpawnPoint> spawns = new ArrayList<>();
		for (JsonElement e : array(o, "spawns", MAX_SPAWNS)) {
			JsonObject j = object(e);
			String kind = need(string(j, "kind", 16));
			if (HoloRules.Kind.of(kind) == null) {
				throw new IllegalArgumentException("spawn kind");
			}

			double[] p = position(j.get("p"), 3);
			spawns.add(new SpawnPoint(kind, need(string(j, "id", ID_MAX)), need(string(j, "name", NAME_MAX)), p[0], p[1], p[2],
				need(string(j, "district", NAME_MAX)), need(string(j, "owner", NAME_MAX)), bool(j, "ok"), need(string(j, "why", 64))));
		}

		List<Layer> layers = new ArrayList<>();
		for (JsonElement e : array(o, "layers", MAX_LAYERS)) {
			JsonObject j = object(e);
			String id = need(string(j, "id", 24));
			if (!LAYER_ID.matcher(id).matches()) {
				throw new IllegalArgumentException("layer id");
			}

			List<LayerItem> items = new ArrayList<>();
			for (JsonElement ie : array(j, "items", MAX_ITEMS)) {
				JsonObject k = object(ie);
				double[] p = position(k.get("p"), 2);
				String label = k.has("label") ? need(string(k, "label", NAME_MAX)) : "";
				items.add(new LayerItem(p[0], p[1], (float) number(k, "v", 0, 1), optColour(k), label));
			}

			layers.add(new Layer(id, List.copyOf(items)));
		}

		List<Detail> detail = null;
		if (o.has("detail")) {
			detail = new ArrayList<>();
			for (JsonElement e : array(o, "detail", MAX_DETAIL)) {
				JsonObject j = object(e);
				int n = (int) number(j, "n", 1, MAX_DETAIL_N);
				byte[] h = base64(j, "h"), c = base64(j, "c");
				if (h.length != n * n * 2 || c.length != n * n) {
					throw new IllegalArgumentException("detail size");
				}

				short[] heights = new short[n * n];
				ByteBuffer.wrap(h).asShortBuffer().get(heights);
				detail.add(new Detail((int) number(j, "x0", -MAX_COORD, MAX_COORD), (int) number(j, "z0", -MAX_COORD, MAX_COORD), n, heights, c));
			}

			detail = List.copyOf(detail);
		}

		return new State(bool(o, "open"), bool(o, "match"), role, bool(o, "hidden"), (int) number(o, "cooldown", 0, 3600), List.copyOf(pins),
			List.copyOf(spawns), List.copyOf(layers), detail);
	}

	// ---- DLL wire (shared/protocol.md vteleport / vteleported, why "holo") ----

	public static final int REASON_MAX = 128;

	/** vteleported {id, ok, reason, p}; p is null when absent (a failure needs none). */
	public record Answer(int id, boolean ok, String reason, double @Nullable [] p) {
	}

	/** {"t":"vteleport","id":..,"p":[x,y,z],"yaw":..,"why":"holo"}: Minecraft position and yaw, like Meet Up's. */
	public static String vteleport(final int id, final double x, final double y, final double z, final float yaw) {
		return String.format(java.util.Locale.ROOT, "{\"t\":\"vteleport\",\"id\":%d,\"p\":[%.3f,%.3f,%.3f],\"yaw\":%.2f,\"why\":\"holo\"}", id, x, y, z, yaw);
	}

	/** A valid vteleported, or null: id whole, ok boolean, reason a string of at most 128 bytes (absent = ""), p three numbers when ok. */
	public static @Nullable Answer parseAnswer(final JsonObject m) {
		if (!(m.get("id") instanceof JsonPrimitive id) || !id.isNumber() || id.getAsDouble() != Math.rint(id.getAsDouble())
			|| Math.abs(id.getAsDouble()) > Integer.MAX_VALUE || !(m.get("ok") instanceof JsonPrimitive ok) || !ok.isBoolean()) {
			return null;
		}

		String reason = "";
		JsonElement r = m.get("reason");
		if (r != null && !r.isJsonNull()) {
			if (!(r instanceof JsonPrimitive rp) || !rp.isString() || rp.getAsString().getBytes(StandardCharsets.UTF_8).length > REASON_MAX) {
				return null;
			}

			reason = rp.getAsString();
		}

		double[] p = null;
		if (m.has("p")) {
			try {
				p = position(m.get("p"), 3);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		return ok.getAsBoolean() && p == null ? null : new Answer(id.getAsInt(), ok.getAsBoolean(), reason, p);
	}

	// ---- helpers ----

	/** {@code s} cut to at most {@code bytes} UTF-8 bytes (never in the middle of a character). */
	public static String cap(final String s, final int bytes) {
		if (s.getBytes(StandardCharsets.UTF_8).length <= bytes) {
			return s;
		}

		StringBuilder out = new StringBuilder();
		int used = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
			if (used + len > bytes) {
				break;
			}

			out.appendCodePoint(cp);
			used += len;
			i += Character.charCount(cp);
		}

		return out.toString();
	}

	public static String colour(final int rgb) {
		return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	private static double round(final double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	private static JsonArray xyz(final double x, final double y, final double z) {
		JsonArray a = new JsonArray();
		a.add(round(x));
		a.add(round(y));
		a.add(round(z));
		return a;
	}

	private static @Nullable String string(final JsonObject o, final String key, final int maxBytes) {
		if (!(o.get(key) instanceof JsonPrimitive p) || !p.isString()) {
			return null;
		}

		String s = p.getAsString();
		return s.getBytes(StandardCharsets.UTF_8).length <= maxBytes ? s : null;
	}

	private static String need(final @Nullable String s) {
		if (s == null) {
			throw new IllegalArgumentException("missing string");
		}

		return s;
	}

	private static boolean bool(final JsonObject o, final String key) {
		if (!(o.get(key) instanceof JsonPrimitive p) || !p.isBoolean()) {
			throw new IllegalArgumentException(key);
		}

		return p.getAsBoolean();
	}

	private static double number(final JsonObject o, final String key, final double min, final double max) {
		if (!(o.get(key) instanceof JsonPrimitive p) || !p.isNumber() || !(p.getAsDouble() >= min && p.getAsDouble() <= max)) {
			throw new IllegalArgumentException(key);
		}

		return p.getAsDouble();
	}

	private static int optColour(final JsonObject o) {
		if (!o.has("col")) {
			return -1;
		}

		String s = need(string(o, "col", 7));
		if (!COLOUR.matcher(s).matches()) {
			throw new IllegalArgumentException("col");
		}

		return Integer.parseInt(s.substring(1), 16);
	}

	private static double[] position(final @Nullable JsonElement e, final int size) {
		if (!(e instanceof JsonArray a) || a.size() != size) {
			throw new IllegalArgumentException("p");
		}

		double[] p = new double[size];
		for (int i = 0; i < size; i++) {
			if (!(a.get(i) instanceof JsonPrimitive n) || !n.isNumber() || !(Math.abs(n.getAsDouble()) < MAX_COORD)) {
				throw new IllegalArgumentException("p");
			}

			p[i] = n.getAsDouble();
		}

		return p;
	}

	private static List<JsonElement> array(final JsonObject o, final String key, final int max) {
		if (!(o.get(key) instanceof JsonArray a) || a.size() > max) {
			throw new IllegalArgumentException(key);
		}

		return a.asList();
	}

	private static JsonObject object(final JsonElement e) {
		if (!e.isJsonObject()) {
			throw new IllegalArgumentException("entry");
		}

		return e.getAsJsonObject();
	}

	private static byte[] base64(final JsonObject o, final String key) {
		try {
			return Base64.getDecoder().decode(need(string(o, key, 1 << 20)));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(key);
		}
	}
}
