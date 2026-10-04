package dev.cyberblock.mode.match;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * One match mode, the {@code match_mode} content kind (megaplan section 1.7; the schema is package 8A's
 * {@code shared/content-schemas/match_mode.schema.json}, its defaults are {@code data/<namespace>/cyberblock/match_mode/<name>.json}
 * and {@code content_check} validates them against it). The loader reads that schema:
 *
 * <pre>
 * {"schema": 1, "name", "description", "teams": {min, max} 2..8, "team_size": {min, max} 1..16, "time_limit_s" 60..7200,
 *  "score_limit" 1..10000, "scoring": {"kill", "capture"} 0..100, "respawn_s" 0..120, "friendly_fire", "pvp",
 *  "zone": {min_radius, max_radius} 16..1024, "capture_points"?: {count 1..9, radius 3..32, seconds 3..120}, "districts",
 *  "reputation", "heat_scale" 0..2, "roles": [teammate|hacker|spectator|overlord], "team_palette": [{name, colour, faction?}] 2..8}
 * </pre>
 *
 * A match has up to {@code teams.max} teams, the first ones of {@code team_palette}; it fills {@code teams.min} of them and opens the
 * next one only when those are full at {@code team_size.max} (free for all: many teams of one). {@code scoring.kill} is what a
 * takedown scores, {@code scoring.capture} what each held capture point scores every second. A creator's zone radius is clamped to
 * {@code zone}, its time to {@code time_limit_s}. {@code roles} are the extra roles a Minecraft-only player may join as: every mode
 * has players, so "teammate" (the fighting role) is always allowed, also when a mode leaves it out because its players have no
 * teammates (free for all). {@code respawn_s} 0 means a defeated player is out until the match ends (last team standing); a
 * positive wait is carried for the spawn screen. A {@code faction} that is not one of {@link #GANGS} means no reputation for that
 * team. Unknown fields are ignored (content_check refuses them). When no file loads at all the {@link #BUILT_IN} modes are offered,
 * so the Match Terminal always works.
 */
public record MatchMode(String name, String description, int minTeams, List<TeamSpec> teams, int teamSizeMin, int teamSizeMax, int timeLimit,
	int scoreLimit, int killScore, int captureScore, int respawnS, boolean friendlyFire, boolean pvp, int zoneMin, int zoneMax, int captureCount,
	int captureRadius, int captureSeconds, boolean districts, boolean reputation, double heatScale, Set<Match.Role> roles) {

	public static final String KIND = "match_mode";
	/** The default zone radius when the creator picks a spot without sizing it (clamped to the mode's zone). */
	static final int DEFAULT_RADIUS = 96;

	/** A team of a mode: its name, its colour (0xRRGGBB) and its Night City gang ("" none). */
	public record TeamSpec(String name, int colour, String gang) {
	}

	/** Cyberpunk 2.31's attitude group names (phase0-feasibility.md, check e) that a team may stand for. */
	public static final Set<String> GANGS = Set.of("tygerClaws", "maelstrom", "valentinos", "6thStreet", "animals", "voodooBoys",
		"scavenger", "wraiths", "aldecaldos", "kangtao", "kurtz_army", "arasaka", "militech");

	/** Offered when no {@code match_mode} file loaded (ids in the cyberblock namespace; the same values as 8A's two defaults). */
	public static final Map<String, MatchMode> BUILT_IN = builtIn();

	/** Players a match needs on teams before it may start: {@code team_size.min} on each of {@code teams.min} teams. */
	public int minPlayers() {
		return this.teamSizeMin * this.minTeams;
	}

	/** Gang Wars style: the zone holds capture points. */
	public boolean capture() {
		return this.captureCount > 0;
	}

	/** "capture" when the mode has capture points, else "kills" (the terminal's label). */
	public String scoringKey() {
		return this.capture() ? "capture" : "kills";
	}

	public int clampRadius(final double r) {
		return (int) Math.round(Math.clamp(r, this.zoneMin, this.zoneMax));
	}

	public int defaultRadius() {
		return this.clampRadius(DEFAULT_RADIUS);
	}

	/** May a player join as {@code role}? Teammate always; the other roles when the mode lists them. */
	public boolean allows(final Match.Role role) {
		return role == Match.Role.TEAMMATE || this.roles.contains(role);
	}

	private static Map<String, MatchMode> builtIn() {
		Set<Match.Role> all = EnumSet.allOf(Match.Role.class);
		Map<String, MatchMode> out = new LinkedHashMap<>();
		out.put("cyberblock:team_deathmatch", new MatchMode("Team Deathmatch", "Two or more teams, one area: every takedown counts.", 2,
			List.of(new TeamSpec("Red", 0xE5384F, ""), new TeamSpec("Blue", 0x3A7BFF, ""), new TeamSpec("Green", 0x35D07F, ""),
				new TeamSpec("Yellow", 0xF2C230, "")), 1, 8, 900, 30, 1, 0, 8, false, true, 32, 160, 0, 0, 0, false, false, 0.5, Set.copyOf(all)));
		out.put("cyberblock:gang_wars", new MatchMode("Gang Wars", "Teams stand for gangs and fight over the area's capture points.", 2,
			List.of(new TeamSpec("Tyger Claws", 0xD4372C, "tygerClaws"), new TeamSpec("Valentinos", 0xE3B02A, "valentinos"),
				new TeamSpec("Maelstrom", 0xC2C7D4, "maelstrom"), new TeamSpec("6th Street", 0x3A9D5D, "6thStreet")), 2, 8, 1800, 300, 2, 1, 12, false,
			true, 96, 320, 3, 8, 20, true, true, 1.0, Set.copyOf(all)));
		return Map.copyOf(out);
	}

	/** Parses one file (ContentKind parser); throws IllegalArgumentException with the reason. */
	public static MatchMode parse(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "name", 48);
		String description = Content.string(json, "description", 200);
		if (name.isBlank() || description.isBlank()) {
			throw new IllegalArgumentException("name and description must not be empty");
		}

		int[] teams = range(json, "teams", "min", "max", 2, 8);
		int[] size = range(json, "team_size", "min", "max", 1, 16);
		int[] zone = range(json, "zone", "min_radius", "max_radius", 16, 1024);
		JsonObject scoring = object(json, "scoring");
		List<TeamSpec> palette = palette(json.get("team_palette"));
		if (palette.size() < teams[0]) {
			throw new IllegalArgumentException("team_palette must name at least teams.min (" + teams[0] + ") teams");
		}

		int count = 0, radius = 0, seconds = 0;
		if (json.has("capture_points")) {
			JsonObject cp = object(json, "capture_points");
			count = number(cp, "count", 1, 9);
			radius = number(cp, "radius", 3, 32);
			seconds = number(cp, "seconds", 3, 120);
		}

		JsonElement heat = json.get("heat_scale");
		if (heat == null || !heat.isJsonPrimitive() || !heat.getAsJsonPrimitive().isNumber() || !(heat.getAsDouble() >= 0.0 && heat.getAsDouble() <= 2.0)) {
			throw new IllegalArgumentException("heat_scale must be a number 0..2");
		}

		return new MatchMode(name, description, teams[0], List.copyOf(palette.subList(0, Math.min(teams[1], palette.size()))), size[0], size[1],
			number(json, "time_limit_s", 60, 7200), number(json, "score_limit", 1, 10_000), number(scoring, "kill", 0, 100),
			number(scoring, "capture", 0, 100), number(json, "respawn_s", 0, 120), bool(json, "friendly_fire"), bool(json, "pvp"), zone[0], zone[1],
			count, radius, seconds, bool(json, "districts"), bool(json, "reputation"), heat.getAsDouble(), roles(json.get("roles")));
	}

	private static List<TeamSpec> palette(final JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2 || e.getAsJsonArray().size() > 8) {
			throw new IllegalArgumentException("team_palette must be a list of 2 to 8 teams");
		}

		List<TeamSpec> out = new ArrayList<>();
		for (JsonElement t : e.getAsJsonArray()) {
			if (!t.isJsonObject()) {
				throw new IllegalArgumentException("each team_palette entry must be an object");
			}

			JsonObject o = t.getAsJsonObject();
			String name = Content.string(o, "name", 32);
			if (name.isBlank()) {
				throw new IllegalArgumentException("a team name must not be empty");
			}

			String faction = o.has("faction") ? Content.string(o, "faction", 32) : "";
			out.add(new TeamSpec(name, Content.colour(o, "colour"), GANGS.contains(faction) ? faction : ""));
		}

		return out;
	}

	private static Set<Match.Role> roles(final JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > 4) {
			throw new IllegalArgumentException("roles must be a list of 1 to 4 roles");
		}

		Set<Match.Role> out = EnumSet.noneOf(Match.Role.class);
		for (JsonElement r : e.getAsJsonArray()) {
			Match.Role role = r.isJsonPrimitive() && r.getAsJsonPrimitive().isString() ? Match.Role.of(r.getAsString()) : null;
			if (role == null || !out.add(role)) {
				throw new IllegalArgumentException("roles holds teammate, hacker, spectator or overlord, each once");
			}
		}

		return Set.copyOf(out);
	}

	/** A required {min, max} object with min <= max, both in lo..hi. */
	private static int[] range(final JsonObject json, final String key, final String min, final String max, final int lo, final int hi) {
		JsonObject o = object(json, key);
		int a = number(o, min, lo, hi), b = number(o, max, lo, hi);
		if (a > b) {
			throw new IllegalArgumentException(key + "." + min + " must not be above " + key + "." + max);
		}

		return new int[] {a, b};
	}

	private static JsonObject object(final JsonObject json, final String key) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException(key + " must be an object");
		}

		return e.getAsJsonObject();
	}

	private static boolean bool(final JsonObject json, final String key) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be true or false");
		}

		return e.getAsBoolean();
	}

	private static int number(final JsonObject json, final String key, final int min, final int max) {
		JsonElement e = json.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be a number");
		}

		double v = e.getAsDouble();
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(key + " must be a whole number " + min + ".." + max);
		}

		return (int) v;
	}
}
