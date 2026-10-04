package dev.cyberblock.mode.match;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * One match mode, the {@code match_mode} content kind (megaplan section 1.7; defaults by package 8A in
 * {@code data/<namespace>/cyberblock/match_mode/<name>.json}). The file:
 *
 * <pre>
 * {"schema": 1, "name": str &lt;= 48, "description"?: str &lt;= 256,
 *  "teams": [{"name": str &lt;= 24, "colour": "#RRGGBB", "gang"?: attitude group}] (2 to 4; default two plain teams),
 *  "scoring"?: "kills" | "capture" (default "kills"), "pvp"?: bool (default true), "friendlyFire"?: bool (default false),
 *  "timeLimit"?: seconds 60..3600 (default 600), "scoreLimit"?: 0..10000 (0 = none, default 0),
 *  "capturePoints"?: 0..6 (default 3 for capture, else 0), "captureSeconds"?: 3..120 (default 10),
 *  "zoneRadius"?: 16..512 blocks (default 96), "minPlayers"?: 1..32 (default 2)}
 * </pre>
 *
 * {@code color} is read as {@code colour}; unknown fields are ignored. {@code gang} is one of Cyberpunk's attitude groups (Phase 0
 * e: {@link #GANGS}); an unknown name means the team has no gang (no reputation change). When no file loads at all the two
 * {@link #BUILT_IN} modes are offered, so the Match Terminal always works.
 */
public record MatchMode(String name, String description, List<TeamSpec> teams, Scoring scoring, boolean pvp, boolean friendlyFire,
	int timeLimit, int scoreLimit, int capturePoints, int captureSeconds, int zoneRadius, int minPlayers) {

	public static final String KIND = "match_mode";

	/** A team of a mode: its name, its colour (0xRRGGBB) and its Night City gang ("" none). */
	public record TeamSpec(String name, int colour, String gang) {
	}

	public enum Scoring {
		KILLS, CAPTURE;

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** Cyberpunk 2.31's attitude group names (phase0-feasibility.md, check e) that a team may stand for. */
	public static final Set<String> GANGS = Set.of("tygerClaws", "maelstrom", "valentinos", "6thStreet", "animals", "voodooBoys",
		"scavenger", "wraiths", "aldecaldos", "kangtao", "kurtz_army", "arasaka", "militech");

	/** Offered when no {@code match_mode} file loaded (ids in the cyberblock namespace). */
	public static final Map<String, MatchMode> BUILT_IN = builtIn();

	public boolean capture() {
		return this.scoring == Scoring.CAPTURE;
	}

	private static Map<String, MatchMode> builtIn() {
		Map<String, MatchMode> out = new LinkedHashMap<>();
		out.put("cyberblock:team_deathmatch", new MatchMode("Team Deathmatch", "Two crews, one zone: the most takedowns when the clock runs out wins.",
			List.of(new TeamSpec("Red", 0xE03030, ""), new TeamSpec("Blue", 0x3070E0, "")), Scoring.KILLS, true, false, 600, 0, 0, 10, 96, 2));
		out.put("cyberblock:gang_wars", new MatchMode("Gang Wars", "Hold the district's corners: every second a point is yours scores for your gang.",
			List.of(new TeamSpec("Tyger Claws", 0xE040A0, "tygerClaws"), new TeamSpec("Maelstrom", 0xD02020, "maelstrom")), Scoring.CAPTURE, true, false,
			900, 600, 3, 10, 128, 2));
		return Map.copyOf(out);
	}

	/** Parses one file (ContentKind parser); throws IllegalArgumentException with the reason. */
	public static MatchMode parse(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "name", 48);
		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be empty");
		}

		String description = json.has("description") ? Content.string(json, "description", 256) : "";
		List<TeamSpec> teams = teams(json.get("teams"));
		Scoring scoring = Scoring.KILLS;
		if (json.has("scoring")) {
			String s = Content.string(json, "scoring", 16);
			scoring = switch (s) {
				case "kills" -> Scoring.KILLS;
				case "capture" -> Scoring.CAPTURE;
				default -> throw new IllegalArgumentException("scoring must be kills or capture");
			};
		}

		return new MatchMode(name, description, teams, scoring, bool(json, "pvp", true), bool(json, "friendlyFire", false),
			number(json, "timeLimit", 60, 3600, 600), number(json, "scoreLimit", 0, 10_000, 0),
			number(json, "capturePoints", 0, 6, scoring == Scoring.CAPTURE ? 3 : 0), number(json, "captureSeconds", 3, 120, 10),
			number(json, "zoneRadius", 16, 512, 96), number(json, "minPlayers", 1, 32, 2));
	}

	private static List<TeamSpec> teams(final JsonElement e) {
		if (e == null) {
			return List.of(new TeamSpec("Red", 0xE03030, ""), new TeamSpec("Blue", 0x3070E0, ""));
		}

		if (!e.isJsonArray() || e.getAsJsonArray().size() < 2 || e.getAsJsonArray().size() > 4) {
			throw new IllegalArgumentException("teams must be a list of 2 to 4 teams");
		}

		List<TeamSpec> out = new ArrayList<>();
		JsonArray a = e.getAsJsonArray();
		for (JsonElement t : a) {
			if (!t.isJsonObject()) {
				throw new IllegalArgumentException("each team must be an object");
			}

			JsonObject o = t.getAsJsonObject();
			if (!o.has("colour") && o.has("color")) {
				o = o.deepCopy();
				o.add("colour", o.get("color"));
			}

			String name = Content.string(o, "name", 24);
			if (name.isBlank()) {
				throw new IllegalArgumentException("a team name must not be empty");
			}

			String gang = o.has("gang") ? Content.string(o, "gang", 32) : "";
			out.add(new TeamSpec(name, Content.colour(o, "colour"), GANGS.contains(gang) ? gang : ""));
		}

		return List.copyOf(out);
	}

	private static boolean bool(final JsonObject json, final String key, final boolean fallback) {
		JsonElement e = json.get(key);
		if (e == null) {
			return fallback;
		}

		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be true or false");
		}

		return e.getAsBoolean();
	}

	private static int number(final JsonObject json, final String key, final int min, final int max, final int fallback) {
		JsonElement e = json.get(key);
		if (e == null) {
			return fallback;
		}

		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be a number");
		}

		double v = e.getAsDouble();
		if (!(v >= min && v <= max) || v != Math.rint(v)) {
			throw new IllegalArgumentException(key + " must be a whole number " + min + ".." + max);
		}

		return (int) v;
	}
}
