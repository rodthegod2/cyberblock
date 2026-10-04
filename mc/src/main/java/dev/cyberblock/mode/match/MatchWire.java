package dev.cyberblock.mode.match;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Match Terminal's wire (megaplan 8-M1), both sides, pure and tested. Minecraft and server only: nothing reaches a DLL.
 *
 * <p>Client to server, {@code cyberblock:act t:"match"}: {@code {"op": ...}} with
 * <ul>
 * <li>{@code open}: send me the state (the screen is open);</li>
 * <li>{@code create}: {@code mode} (a {@code match_mode} id), {@code zone:{x, z, r 16..1024, district? <= 64}} (the mode clamps r to
 * its zone range), {@code time} 60..7200 s (the mode caps it at its time_limit_s),
 * {@code pvp} bool, {@code invite} bool (invite only); the creator joins as a teammate;</li>
 * <li>{@code join}: {@code id}, {@code role} one of teammate, hacker, spectator, overlord, {@code team}? (-1 or absent: the smallest);</li>
 * <li>{@code leave}; {@code start} and {@code end} (the creator); {@code invite}: {@code name} (a player name, 1-16 of A-Z a-z 0-9 _).</li>
 * </ul>
 *
 * <p>Server to client, {@code cyberblock:ui t:"match"}: {@link State} as {@code {"open", "canCreate", "why", "you", "team", "role",
 * "pvp":{"on","lock"}, "modes":[...], "districts":[...], "matches":[...]}}; sent when the terminal opens (open true), after every
 * change to a match the player sees, and once a second to the players of running matches (their HUD).
 */
public final class MatchWire {
	public static final String T = "match";
	public static final Set<String> OPS = Set.of("open", "create", "join", "leave", "start", "end", "invite");
	private static final Pattern ID = Pattern.compile("m[0-9]{1,6}");
	private static final Pattern MODE = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	/** A parsed, validated request. Fields an op does not use are "", -1 or null. */
	public record Request(String op, String id, String mode, Match.@Nullable Role role, int team, String name, Match.@Nullable Zone zone, int time,
		boolean pvp, boolean invite) {
	}

	public record Member(String name, String role, boolean linked) {
	}

	public record Team(String name, int colour, int score, List<Member> members) {
	}

	public record Point(String name, double x, double z, double r, int owner, int holder, double progress) {
	}

	public record View(String id, String mode, String name, String creator, String state, boolean invite, boolean invited, boolean pvp, double x,
		double z, double r, String district, int time, int winner, List<Team> teams, List<Member> others, List<Point> points) {
	}

	public record Mode(String id, String name, String description, List<Team> teams, boolean pvp, int time, String scoring, int radius) {
	}

	public record District(String name, double x, double z, double r) {
	}

	public record State(boolean open, boolean canCreate, String why, String you, int team, String role, boolean pvpOn, int pvpLock, List<Mode> modes,
		List<District> districts, List<View> matches) {
		/** The view of the match this player is in, or null. */
		public @Nullable View mine() {
			for (View v : this.matches) {
				if (v.id().equals(this.you)) {
					return v;
				}
			}

			return null;
		}
	}

	private MatchWire() {
	}

	/** Validates one {@code act match}; null when anything is off (the server answers {@code cyberblock.match.refused.bad}). */
	public static @Nullable Request request(final JsonObject o) {
		String op = str(o, "op", 8);
		if (op == null || !OPS.contains(op)) {
			return null;
		}

		return switch (op) {
			case "create" -> create(o);
			case "join" -> {
				String id = str(o, "id", 8);
				Match.Role role = Match.Role.of(o.has("role") ? nonNull(str(o, "role", 16)) : "teammate");
				int team = o.has("team") ? whole(o, "team", -1, 3) : -1;
				yield id == null || !ID.matcher(id).matches() || role == null || team == Integer.MIN_VALUE ? null
					: new Request(op, id, "", role, team, "", null, 0, false, false);
			}
			case "invite" -> {
				String name = str(o, "name", 16);
				yield name == null || !NAME.matcher(name).matches() ? null : new Request(op, "", "", null, -1, name, null, 0, false, false);
			}
			default -> new Request(op, "", "", null, -1, "", null, 0, false, false);
		};
	}

	private static @Nullable Request create(final JsonObject o) {
		String mode = str(o, "mode", 98);
		int time = whole(o, "time", MatchRules.MIN_TIME, MatchRules.MAX_TIME);
		JsonElement z = o.get("zone");
		if (mode == null || !MODE.matcher(mode).matches() || time == Integer.MIN_VALUE || z == null || !z.isJsonObject()) {
			return null;
		}

		JsonObject zo = z.getAsJsonObject();
		double x = num(zo, "x", 3.0e7), zz = num(zo, "z", 3.0e7), r = num(zo, "r", MatchRules.MAX_RADIUS);
		String district = zo.has("district") ? str(zo, "district", 64) : "";
		Boolean pvp = bool(o, "pvp"), invite = bool(o, "invite");
		if (Double.isNaN(x) || Double.isNaN(zz) || Double.isNaN(r) || r < MatchRules.MIN_RADIUS || district == null || pvp == null || invite == null) {
			return null;
		}

		return new Request("create", "", mode, Match.Role.TEAMMATE, -1, "", new Match.Zone(x, zz, r, district), time, pvp, invite);
	}

	private static String nonNull(final @Nullable String s) {
		return s == null ? "" : s;
	}

	private static @Nullable String str(final JsonObject o, final String key, final int maxChars) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			return null;
		}

		String s = e.getAsString();
		return s.length() <= maxChars ? s : null;
	}

	private static @Nullable Boolean bool(final JsonObject o, final String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : null;
	}

	/** A whole number in min..max, else Integer.MIN_VALUE. */
	private static int whole(final JsonObject o, final String key, final int min, final int max) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return Integer.MIN_VALUE;
		}

		double v = e.getAsDouble();
		return v >= min && v <= max && v == Math.rint(v) ? (int) v : Integer.MIN_VALUE;
	}

	/** A finite number with |v| <= limit, else NaN. */
	private static double num(final JsonObject o, final String key, final double limit) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return Double.NaN;
		}

		double v = e.getAsDouble();
		return Math.abs(v) <= limit ? v : Double.NaN;
	}

	// ---- server to client

	public static String json(final State s) {
		JsonObject o = new JsonObject();
		o.addProperty("open", s.open());
		o.addProperty("canCreate", s.canCreate());
		o.addProperty("why", s.why());
		o.addProperty("you", s.you());
		o.addProperty("team", s.team());
		o.addProperty("role", s.role());
		JsonObject pvp = new JsonObject();
		pvp.addProperty("on", s.pvpOn());
		pvp.addProperty("lock", s.pvpLock());
		o.add("pvp", pvp);
		JsonArray modes = new JsonArray();
		for (Mode m : s.modes()) {
			JsonObject mo = new JsonObject();
			mo.addProperty("id", m.id());
			mo.addProperty("name", m.name());
			mo.addProperty("desc", m.description());
			mo.add("teams", teams(m.teams()));
			mo.addProperty("pvp", m.pvp());
			mo.addProperty("time", m.time());
			mo.addProperty("scoring", m.scoring());
			mo.addProperty("radius", m.radius());
			modes.add(mo);
		}

		o.add("modes", modes);
		JsonArray districts = new JsonArray();
		for (District d : s.districts()) {
			JsonObject d0 = new JsonObject();
			d0.addProperty("name", d.name());
			d0.addProperty("x", d.x());
			d0.addProperty("z", d.z());
			d0.addProperty("r", d.r());
			districts.add(d0);
		}

		o.add("districts", districts);
		JsonArray matches = new JsonArray();
		for (View v : s.matches()) {
			JsonObject vo = new JsonObject();
			vo.addProperty("id", v.id());
			vo.addProperty("mode", v.mode());
			vo.addProperty("name", v.name());
			vo.addProperty("creator", v.creator());
			vo.addProperty("state", v.state());
			vo.addProperty("invite", v.invite());
			vo.addProperty("invited", v.invited());
			vo.addProperty("pvp", v.pvp());
			JsonObject zone = new JsonObject();
			zone.addProperty("x", v.x());
			zone.addProperty("z", v.z());
			zone.addProperty("r", v.r());
			zone.addProperty("district", v.district());
			vo.add("zone", zone);
			vo.addProperty("time", v.time());
			vo.addProperty("winner", v.winner());
			vo.add("teams", teams(v.teams()));
			vo.add("others", members(v.others()));
			JsonArray points = new JsonArray();
			for (Point p : v.points()) {
				JsonObject po = new JsonObject();
				po.addProperty("name", p.name());
				po.addProperty("x", p.x());
				po.addProperty("z", p.z());
				po.addProperty("r", p.r());
				po.addProperty("owner", p.owner());
				po.addProperty("holder", p.holder());
				po.addProperty("progress", Math.round(p.progress() * 100.0) / 100.0);
				points.add(po);
			}

			vo.add("points", points);
			matches.add(vo);
		}

		o.add("matches", matches);
		return o.toString();
	}

	private static JsonArray teams(final List<Team> teams) {
		JsonArray a = new JsonArray();
		for (Team t : teams) {
			JsonObject to = new JsonObject();
			to.addProperty("name", t.name());
			to.addProperty("colour", t.colour());
			to.addProperty("score", t.score());
			to.add("members", members(t.members()));
			a.add(to);
		}

		return a;
	}

	private static JsonArray members(final List<Member> members) {
		JsonArray a = new JsonArray();
		for (Member m : members) {
			JsonObject mo = new JsonObject();
			mo.addProperty("name", m.name());
			mo.addProperty("role", m.role());
			mo.addProperty("linked", m.linked());
			a.add(mo);
		}

		return a;
	}

	/** The client's side: parses a {@code ui match} message; throws on a malformed one (the route logs and drops it). */
	public static State parse(final String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		JsonObject pvp = o.getAsJsonObject("pvp");
		List<Mode> modes = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("modes")) {
			JsonObject m = e.getAsJsonObject();
			modes.add(new Mode(s(m, "id"), s(m, "name"), s(m, "desc"), teams(m.getAsJsonArray("teams")), m.get("pvp").getAsBoolean(),
				m.get("time").getAsInt(), s(m, "scoring"), m.get("radius").getAsInt()));
		}

		List<District> districts = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("districts")) {
			JsonObject d = e.getAsJsonObject();
			districts.add(new District(s(d, "name"), d.get("x").getAsDouble(), d.get("z").getAsDouble(), d.get("r").getAsDouble()));
		}

		List<View> matches = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("matches")) {
			JsonObject v = e.getAsJsonObject();
			JsonObject zone = v.getAsJsonObject("zone");
			List<Point> points = new ArrayList<>();
			for (JsonElement pe : v.getAsJsonArray("points")) {
				JsonObject p = pe.getAsJsonObject();
				points.add(new Point(s(p, "name"), p.get("x").getAsDouble(), p.get("z").getAsDouble(), p.get("r").getAsDouble(), p.get("owner").getAsInt(),
					p.get("holder").getAsInt(), p.get("progress").getAsDouble()));
			}

			matches.add(new View(s(v, "id"), s(v, "mode"), s(v, "name"), s(v, "creator"), s(v, "state"), v.get("invite").getAsBoolean(),
				v.get("invited").getAsBoolean(), v.get("pvp").getAsBoolean(), zone.get("x").getAsDouble(), zone.get("z").getAsDouble(),
				zone.get("r").getAsDouble(), s(zone, "district"), v.get("time").getAsInt(), v.get("winner").getAsInt(), teams(v.getAsJsonArray("teams")),
				members(v.getAsJsonArray("others")), List.copyOf(points)));
		}

		return new State(o.get("open").getAsBoolean(), o.get("canCreate").getAsBoolean(), s(o, "why"), s(o, "you"), o.get("team").getAsInt(),
			s(o, "role"), pvp.get("on").getAsBoolean(), pvp.get("lock").getAsInt(), List.copyOf(modes), List.copyOf(districts), List.copyOf(matches));
	}

	private static String s(final JsonObject o, final String key) {
		return o.get(key).getAsString();
	}

	private static List<Team> teams(final JsonArray a) {
		List<Team> out = new ArrayList<>();
		for (JsonElement e : a) {
			JsonObject t = e.getAsJsonObject();
			out.add(new Team(s(t, "name"), t.get("colour").getAsInt(), t.get("score").getAsInt(), members(t.getAsJsonArray("members"))));
		}

		return List.copyOf(out);
	}

	private static List<Member> members(final JsonArray a) {
		List<Member> out = new ArrayList<>();
		for (JsonElement e : a) {
			JsonObject m = e.getAsJsonObject();
			out.add(new Member(s(m, "name"), s(m, "role"), m.get("linked").getAsBoolean()));
		}

		return List.copyOf(out);
	}
}
