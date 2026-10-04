package dev.cyberblock.mode.spectate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The caster overlay's data ({@code cyberblock:ui t:"caster"}, megaplan 8-T3; 8P documents the row), once a second to each spectator
 * and once with {@code on:false} when it stops:
 * <pre>
 * {"on":bool, "title":"", "timer":-1|seconds, "heat":0..100, "tier":0..5,
 *  "teams":[{"name":"", "colour":0xRRGGBB, "players":[{"name":"", "down":bool}]}],
 *  "board":{"title":"", "rows":[{"name":"", "v":int}]},
 *  "feed":[{"a":"", "verb":"", "b":"", "age":seconds}]}
 * </pre>
 * At most {@link #TEAMS} teams of {@link #PLAYERS} players, {@link #ROWS} board rows and {@link KillFeed#CAP} feed entries; names at
 * most {@link #NAME_CAP} bytes.
 */
public final class CasterWire {
	public static final String T = "caster";
	public static final int TEAMS = 8, PLAYERS = 16, ROWS = 8, NAME_CAP = 64;

	public record Member(String name, boolean down) {
	}

	public record Team(String name, int colour, List<Member> players) {
	}

	public record Row(String name, int value) {
	}

	public record Feed(String who, String verb, String whom, int age) {
	}

	/** Everything the overlay shows. */
	public record View(boolean on, String title, int timer, int heat, int tier, List<Team> teams, String boardTitle, List<Row> board, List<Feed> feed) {
		public static final View OFF = new View(false, "", -1, 0, 0, List.of(), "", List.of(), List.of());
	}

	private CasterWire() {
	}

	public static String write(final View v) {
		JsonObject o = new JsonObject();
		o.addProperty("on", v.on());
		o.addProperty("title", cap(v.title()));
		o.addProperty("timer", Math.max(-1, v.timer()));
		o.addProperty("heat", Math.clamp(v.heat(), 0, 100));
		o.addProperty("tier", Math.clamp(v.tier(), 0, 5));
		JsonArray teams = new JsonArray();
		for (Team t : v.teams().subList(0, Math.min(TEAMS, v.teams().size()))) {
			JsonObject to = new JsonObject();
			to.addProperty("name", cap(t.name()));
			to.addProperty("colour", t.colour() & 0xFFFFFF);
			JsonArray players = new JsonArray();
			for (Member m : t.players().subList(0, Math.min(PLAYERS, t.players().size()))) {
				JsonObject mo = new JsonObject();
				mo.addProperty("name", cap(m.name()));
				mo.addProperty("down", m.down());
				players.add(mo);
			}

			to.add("players", players);
			teams.add(to);
		}

		o.add("teams", teams);
		JsonObject board = new JsonObject();
		board.addProperty("title", cap(v.boardTitle()));
		JsonArray rows = new JsonArray();
		for (Row r : v.board().subList(0, Math.min(ROWS, v.board().size()))) {
			JsonObject ro = new JsonObject();
			ro.addProperty("name", cap(r.name()));
			ro.addProperty("v", r.value());
			rows.add(ro);
		}

		board.add("rows", rows);
		o.add("board", board);
		JsonArray feed = new JsonArray();
		for (Feed f : v.feed().subList(0, Math.min(KillFeed.CAP, v.feed().size()))) {
			JsonObject fo = new JsonObject();
			fo.addProperty("a", cap(f.who()));
			fo.addProperty("verb", cap(f.verb()));
			fo.addProperty("b", cap(f.whom()));
			fo.addProperty("age", Math.max(0, f.age()));
			feed.add(fo);
		}

		o.add("feed", feed);
		return o.toString();
	}

	/** The client's parse; null for anything malformed or over the caps (dropped). */
	public static @Nullable View parse(final String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			if (!o.get("on").getAsBoolean()) {
				return View.OFF;
			}

			int timer = o.get("timer").getAsInt(), heat = o.get("heat").getAsInt(), tier = o.get("tier").getAsInt();
			if (timer < -1 || heat < 0 || heat > 100 || tier < 0 || tier > 5) {
				return null;
			}

			List<Team> teams = new ArrayList<>();
			for (JsonElement te : capped(o.getAsJsonArray("teams"), TEAMS)) {
				JsonObject to = te.getAsJsonObject();
				List<Member> players = new ArrayList<>();
				for (JsonElement pe : capped(to.getAsJsonArray("players"), PLAYERS)) {
					players.add(new Member(name(pe.getAsJsonObject(), "name"), pe.getAsJsonObject().get("down").getAsBoolean()));
				}

				teams.add(new Team(name(to, "name"), to.get("colour").getAsInt() & 0xFFFFFF, List.copyOf(players)));
			}

			JsonObject board = o.getAsJsonObject("board");
			List<Row> rows = new ArrayList<>();
			for (JsonElement re : capped(board.getAsJsonArray("rows"), ROWS)) {
				rows.add(new Row(name(re.getAsJsonObject(), "name"), re.getAsJsonObject().get("v").getAsInt()));
			}

			List<Feed> feed = new ArrayList<>();
			for (JsonElement fe : capped(o.getAsJsonArray("feed"), KillFeed.CAP)) {
				JsonObject fo = fe.getAsJsonObject();
				feed.add(new Feed(name(fo, "a"), name(fo, "verb"), name(fo, "b"), Math.max(0, fo.get("age").getAsInt())));
			}

			return new View(true, name(o, "title"), timer, heat, tier, List.copyOf(teams), name(board, "title"), List.copyOf(rows), List.copyOf(feed));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static JsonArray capped(final JsonArray a, final int cap) {
		if (a.size() > cap) {
			throw new IllegalArgumentException("over the cap");
		}

		return a;
	}

	private static String name(final JsonObject o, final String key) {
		String s = o.get(key).getAsString();
		if (s.getBytes(StandardCharsets.UTF_8).length > NAME_CAP) {
			throw new IllegalArgumentException(key + " too long");
		}

		return s;
	}

	private static String cap(final String s) {
		return s.getBytes(StandardCharsets.UTF_8).length <= NAME_CAP ? s : s.substring(0, NAME_CAP / 4);
	}
}
