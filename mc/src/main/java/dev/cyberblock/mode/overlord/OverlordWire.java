package dev.cyberblock.mode.overlord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Overlord Console's messages (megaplan section 1.3 {@code cyberblock:act} and {@code cyberblock:ui}; both sides use this class).
 *
 * <p>Client to server ({@code act}): {@code ovlpoll {}} once a second while the console is open; {@code ovlspawn {id, x, z}} a palette
 * entry at a map point; {@code ovlevent {id, x, z}} an event at a map point. The server finds the ground height itself.
 *
 * <p>Server to client ({@code ui ovl}): the console state: {@code open} (true on the first state: the client opens the console),
 * {@code match}, {@code tp}/{@code tpMax}/{@code regen} (Threat Points), {@code intensity}, {@code live}/{@code cap} (Overlord spawns
 * alive), {@code minDist}, {@code director} (the Auto-Director runs this match), {@code area} [x0, z0, x1, z1] or absent, {@code players}
 * [{name, x, z, opted, linked}], {@code spawns} and {@code events} [{id, name, desc, group, nc, cost, count, cooldown, ok}] (cooldown = seconds of
 * cooldown left, ok = affordable and ready), {@code pending} [{kind, name, x, z, left, r}] (left = ticks until it lands), {@code timeline}
 * [{ago, text}] (newest first) and {@code note} (why the console is read-only, or "").
 */
public final class OverlordWire {
	public static final String T_POLL = "ovlpoll", T_SPAWN = "ovlspawn", T_EVENT = "ovlevent", UI_STATE = "ovl";
	public static final int MAX_LIST = 64, MAX_TIMELINE = 24, NAME_MAX = 48, TEXT_MAX = 200, ID_MAX = 128;

	public record Player(String name, double x, double z, boolean opted, boolean linked) {
	}

	public record Option(String id, String name, String desc, String group, boolean nc, int cost, int count, int cooldown, boolean ok) {
	}

	public record Mark(String kind, String name, double x, double z, int left, int r) {
	}

	public record Line(int ago, String text) {
	}

	public record State(boolean open, String match, int tp, int tpMax, double regen, String intensity, int live, int cap, int minDist, boolean director,
		double @Nullable [] area, List<Player> players, List<Option> spawns, List<Option> events, List<Mark> pending, List<Line> timeline, String note) {
	}

	/** A spawn or event request: the entry id and the map point. */
	public record Ask(String id, double x, double z) {
	}

	private OverlordWire() {
	}

	// ---- act (client to server) ----

	public static String ask(final String id, final double x, final double z) {
		JsonObject m = new JsonObject();
		m.addProperty("id", id);
		m.addProperty("x", x);
		m.addProperty("z", z);
		return m.toString();
	}

	public static @Nullable Ask parseAsk(final JsonObject m) {
		String id = string(m, "id", ID_MAX);
		Double x = number(m, "x"), z = number(m, "z");
		if (id == null || id.isEmpty() || x == null || z == null || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7) {
			return null;
		}

		return new Ask(id, x, z);
	}

	// ---- ui (server to client) ----

	public static String state(final State s) {
		JsonObject m = new JsonObject();
		m.addProperty("open", s.open());
		m.addProperty("match", cap(s.match(), ID_MAX));
		m.addProperty("tp", s.tp());
		m.addProperty("tpMax", s.tpMax());
		m.addProperty("regen", s.regen());
		m.addProperty("intensity", s.intensity());
		m.addProperty("live", s.live());
		m.addProperty("cap", s.cap());
		m.addProperty("minDist", s.minDist());
		m.addProperty("director", s.director());
		if (s.area() != null) {
			JsonArray a = new JsonArray();
			for (double v : s.area()) {
				a.add(v);
			}

			m.add("area", a);
		}

		JsonArray players = new JsonArray();
		s.players().stream().limit(MAX_LIST).forEach(p -> {
			JsonObject o = new JsonObject();
			o.addProperty("name", cap(p.name(), NAME_MAX));
			o.addProperty("x", p.x());
			o.addProperty("z", p.z());
			o.addProperty("opted", p.opted());
			o.addProperty("linked", p.linked());
			players.add(o);
		});
		m.add("players", players);
		m.add("spawns", options(s.spawns()));
		m.add("events", options(s.events()));
		JsonArray pending = new JsonArray();
		s.pending().stream().limit(MAX_LIST).forEach(p -> {
			JsonObject o = new JsonObject();
			o.addProperty("kind", p.kind());
			o.addProperty("name", cap(p.name(), NAME_MAX));
			o.addProperty("x", p.x());
			o.addProperty("z", p.z());
			o.addProperty("left", p.left());
			o.addProperty("r", p.r());
			pending.add(o);
		});
		m.add("pending", pending);
		JsonArray timeline = new JsonArray();
		s.timeline().stream().limit(MAX_TIMELINE).forEach(l -> {
			JsonObject o = new JsonObject();
			o.addProperty("ago", l.ago());
			o.addProperty("text", cap(l.text(), TEXT_MAX));
			timeline.add(o);
		});
		m.add("timeline", timeline);
		m.addProperty("note", cap(s.note(), TEXT_MAX));
		return m.toString();
	}

	private static JsonArray options(final List<Option> list) {
		JsonArray out = new JsonArray();
		list.stream().limit(MAX_LIST).forEach(e -> {
			JsonObject o = new JsonObject();
			o.addProperty("id", cap(e.id(), ID_MAX));
			o.addProperty("name", cap(e.name(), NAME_MAX));
			o.addProperty("desc", cap(e.desc(), TEXT_MAX));
			o.addProperty("group", e.group());
			o.addProperty("nc", e.nc());
			o.addProperty("cost", e.cost());
			o.addProperty("count", e.count());
			o.addProperty("cooldown", e.cooldown());
			o.addProperty("ok", e.ok());
			out.add(o);
		});
		return out;
	}

	/** The console state, or null when it is malformed (a bad entry of a list is skipped). */
	public static @Nullable State parseState(final JsonObject m) {
		String match = string(m, "match", ID_MAX), intensity = string(m, "intensity", 16), note = string(m, "note", TEXT_MAX * 3);
		Double regen = number(m, "regen");
		if (match == null || intensity == null || regen == null) {
			return null;
		}

		double[] area = null;
		if (m.get("area") instanceof JsonArray a && a.size() == 4) {
			area = new double[4];
			for (int i = 0; i < 4; i++) {
				JsonElement v = a.get(i);
				area[i] = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Double.NaN;
				if (!Double.isFinite(area[i])) {
					return null;
				}
			}
		}

		List<Player> players = new ArrayList<>();
		for (JsonObject o : objects(m, "players")) {
			String name = string(o, "name", NAME_MAX * 3);
			Double x = number(o, "x"), z = number(o, "z");
			if (name != null && x != null && z != null) {
				players.add(new Player(name, x, z, bool(o, "opted"), bool(o, "linked")));
			}
		}

		List<Mark> pending = new ArrayList<>();
		for (JsonObject o : objects(m, "pending")) {
			String kind = string(o, "kind", 16), name = string(o, "name", NAME_MAX * 3);
			Double x = number(o, "x"), z = number(o, "z");
			if (kind != null && name != null && x != null && z != null) {
				pending.add(new Mark(kind, name, x, z, integer(o, "left"), integer(o, "r")));
			}
		}

		List<Line> timeline = new ArrayList<>();
		for (JsonObject o : objects(m, "timeline")) {
			String text = string(o, "text", TEXT_MAX * 3);
			if (text != null) {
				timeline.add(new Line(integer(o, "ago"), text));
			}
		}

		return new State(bool(m, "open"), match, integer(m, "tp"), integer(m, "tpMax"), regen, intensity, integer(m, "live"), integer(m, "cap"),
			integer(m, "minDist"), bool(m, "director"), area, List.copyOf(players), parseOptions(m, "spawns"), parseOptions(m, "events"),
			List.copyOf(pending), List.copyOf(timeline), note == null ? "" : note);
	}

	private static List<Option> parseOptions(final JsonObject m, final String key) {
		List<Option> out = new ArrayList<>();
		for (JsonObject o : objects(m, key)) {
			String id = string(o, "id", ID_MAX), name = string(o, "name", NAME_MAX * 3), desc = string(o, "desc", TEXT_MAX * 3);
			String group = string(o, "group", 32);
			if (id != null && name != null) {
				out.add(new Option(id, name, desc == null ? "" : desc, group == null ? "" : group, bool(o, "nc"), integer(o, "cost"), integer(o, "count"),
					integer(o, "cooldown"), bool(o, "ok")));
			}
		}

		return List.copyOf(out);
	}

	// ---- helpers ----

	/** At most {@code bytes} UTF-8 bytes (cut on a character boundary). */
	static String cap(final String s, final int bytes) {
		if (s.getBytes(StandardCharsets.UTF_8).length <= bytes) {
			return s;
		}

		StringBuilder b = new StringBuilder();
		int used = 0;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
			if (used + n > bytes) {
				break;
			}

			b.appendCodePoint(cp);
			used += n;
			i += Character.charCount(cp);
		}

		return b.toString();
	}

	private static List<JsonObject> objects(final JsonObject m, final String key) {
		List<JsonObject> out = new ArrayList<>();
		if (m.get(key) instanceof JsonArray a) {
			for (JsonElement e : a) {
				if (out.size() == MAX_LIST) {
					break;
				}

				if (e.isJsonObject()) {
					out.add(e.getAsJsonObject());
				}
			}
		}

		return out;
	}

	private static @Nullable String string(final JsonObject m, final String key, final int maxBytes) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			return null;
		}

		String s = e.getAsString();
		return s.getBytes(StandardCharsets.UTF_8).length <= maxBytes ? s : null;
	}

	private static @Nullable Double number(final JsonObject m, final String key) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())) {
			return null;
		}

		return e.getAsDouble();
	}

	private static int integer(final JsonObject m, final String key) {
		Double d = number(m, key);
		return d == null ? 0 : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
	}

	private static boolean bool(final JsonObject m, final String key) {
		JsonElement e = m.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}
}
