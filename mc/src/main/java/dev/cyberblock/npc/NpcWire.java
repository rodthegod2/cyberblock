package dev.cyberblock.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The role screens' messages (megaplan task 7-N1 over section 1.3's {@code cyberblock:ui} and {@code cyberblock:act}). The server
 * decides everything: it sends what a screen shows, the client only shows it and names the row a button was pressed on.
 *
 * <pre>
 * cyberblock:ui  t:"npc"     {"e":812, "role":"shop", "name":"Dax", "title":"cyberblock.role.vendor", "line":"...", "eddies":120,
 *                             "tab":"buy", "tabs":["buy","sell"], "result":"",
 *                             "rows":[{"id":"b1", "item":"minecraft:bread", "n":2, "key":"", "text":"", "sub":"", "price":15, "btn":"buy", "on":true}]}
 * cyberblock:ui  t:"surgery" {"e":812, "implant":"sandevistan", "lo":0.41, "hi":0.59, "period":1600, "timeout":15000}
 * cyberblock:act t:"npc"     {"e":812, "a":"tab|do|cut|abort", "arg":"sell"}
 * </pre>
 * {@code e} is the NPC's entity id; {@code role} a screen of {@link NpcRole#SCREENS}; {@code name} the NPC's own name (≤ 64 bytes,
 * "" for a Night City figure: the client shows the lang key {@code title}); {@code line} the NPC's dialogue line (original content,
 * ≤ 256 bytes); {@code eddies} the player's Minecraft eddies as the server counts them; at most {@link #MAX_TABS} tab codes and
 * {@link #MAX_ROWS} rows. A row: {@code id} ≤ 32 chars, {@code item} an item id for the icon or "", {@code n} 0..512 (the count
 * drawn on the icon is capped at 64), {@code key} a lang key for the row's name with {@code text} as its English (both "": the
 * item's name, or the button's service words), {@code sub} a second line of content text, {@code price} eddies or -1, {@code btn}
 * the button's code ({@code cyberblock.npc.btn.<code>}), {@code on} whether it can be pressed. {@code result} answers the last press
 * ({@code cyberblock.npc.result.<code>}). The surgery's marker runs from 0 to 1 and back once per {@code period} ms from the moment the
 * client gets the bar; a cut inside {@code [lo, hi]} succeeds (the server times the cut itself).
 */
public final class NpcWire {
	public static final String T_NPC = "npc", T_SURGERY = "surgery", ACT = "npc";
	public static final int MAX_TABS = 4, MAX_ROWS = 32, NAME_CAP = 64, LINE_CAP = 256, TEXT_CAP = 96, SUB_CAP = 300, ID_CAP = 32, KEY_CAP = 96;
	private static final Pattern CODE = Pattern.compile("[a-z_]{1,16}");
	private static final Pattern RESULT = Pattern.compile("[a-z_]{0,16}");
	private static final Pattern KEY = Pattern.compile("[a-z0-9_.]{0," + KEY_CAP + "}");
	private static final Pattern ARG = Pattern.compile("[A-Za-z0-9_.:-]{0,32}");
	private static final Pattern ITEM = Pattern.compile("([a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128})?");
	public static final List<String> ACTS = List.of("tab", "do", "cut", "abort");

	public record Row(String id, String item, int n, String key, String text, String sub, int price, String btn, boolean on) {
	}

	public record Screen(int e, String role, String name, String title, String line, int eddies, String tab, List<String> tabs, List<Row> rows,
		String result) {
	}

	public record Surgery(int e, String implant, float lo, float hi, int period, int timeout) {
	}

	public record Act(int e, String a, String arg) {
	}

	private NpcWire() {
	}

	/** Cuts a string to at most cap UTF-8 bytes (whole characters). */
	static String cap(final String s, final int cap) {
		if (s.getBytes(StandardCharsets.UTF_8).length <= cap) {
			return s;
		}

		StringBuilder b = new StringBuilder();
		int bytes = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
			if (bytes + n > cap) {
				break;
			}

			b.appendCodePoint(cp);
			bytes += n;
			i += Character.charCount(cp);
		}

		return b.toString();
	}

	private static boolean within(final String s, final int cap) {
		return s.getBytes(StandardCharsets.UTF_8).length <= cap;
	}

	public static String screen(final Screen s) {
		JsonObject m = new JsonObject();
		m.addProperty("e", s.e());
		m.addProperty("role", s.role());
		m.addProperty("name", cap(s.name(), NAME_CAP));
		m.addProperty("title", s.title());
		m.addProperty("line", cap(s.line(), LINE_CAP));
		m.addProperty("eddies", s.eddies());
		m.addProperty("tab", s.tab());
		JsonArray tabs = new JsonArray();
		s.tabs().stream().limit(MAX_TABS).forEach(tabs::add);
		m.add("tabs", tabs);
		m.addProperty("result", s.result());
		JsonArray rows = new JsonArray();
		for (Row r : s.rows().subList(0, Math.min(MAX_ROWS, s.rows().size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("id", r.id());
			o.addProperty("item", r.item());
			o.addProperty("n", r.n());
			o.addProperty("key", r.key());
			o.addProperty("text", cap(r.text(), TEXT_CAP));
			o.addProperty("sub", cap(r.sub(), SUB_CAP));
			o.addProperty("price", r.price());
			o.addProperty("btn", r.btn());
			o.addProperty("on", r.on());
			rows.add(o);
		}

		m.add("rows", rows);
		return m.toString();
	}

	/** A {@code npc} json, or null when anything is missing or out of its limits. */
	public static @Nullable Screen parseScreen(final JsonObject m) {
		try {
			String role = m.get("role").getAsString(), name = m.get("name").getAsString(), title = m.get("title").getAsString();
			String line = m.get("line").getAsString(), tab = m.get("tab").getAsString(), result = m.get("result").getAsString();
			int e = m.get("e").getAsInt(), eddies = m.get("eddies").getAsInt();
			JsonArray tabsJson = m.getAsJsonArray("tabs"), rowsJson = m.getAsJsonArray("rows");
			if (!NpcRole.SCREENS.contains(role) || !within(name, NAME_CAP) || !KEY.matcher(title).matches() || !within(line, LINE_CAP)
				|| !CODE.matcher(tab).matches() || !RESULT.matcher(result).matches() || eddies < 0 || tabsJson.size() > MAX_TABS
				|| rowsJson.size() > MAX_ROWS) {
				return null;
			}

			List<String> tabs = new ArrayList<>();
			for (JsonElement t : tabsJson) {
				if (!CODE.matcher(t.getAsString()).matches()) {
					return null;
				}

				tabs.add(t.getAsString());
			}

			List<Row> rows = new ArrayList<>();
			for (JsonElement el : rowsJson) {
				JsonObject o = el.getAsJsonObject();
				Row r = new Row(o.get("id").getAsString(), o.get("item").getAsString(), o.get("n").getAsInt(), o.get("key").getAsString(),
					o.get("text").getAsString(), o.get("sub").getAsString(), o.get("price").getAsInt(), o.get("btn").getAsString(), o.get("on").getAsBoolean());
				if (r.id().isEmpty() || r.id().length() > ID_CAP || !ITEM.matcher(r.item()).matches() || r.n() < 0 || r.n() > 512
					|| !KEY.matcher(r.key()).matches() || !within(r.text(), TEXT_CAP) || !within(r.sub(), SUB_CAP) || r.price() < -1
					|| r.price() > 1_000_000 || !CODE.matcher(r.btn()).matches()) {
					return null;
				}

				rows.add(r);
			}

			return new Screen(e, role, name, title, line, eddies, tab, List.copyOf(tabs), List.copyOf(rows), result);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public static String surgery(final Surgery s) {
		JsonObject m = new JsonObject();
		m.addProperty("e", s.e());
		m.addProperty("implant", s.implant());
		m.addProperty("lo", s.lo());
		m.addProperty("hi", s.hi());
		m.addProperty("period", s.period());
		m.addProperty("timeout", s.timeout());
		return m.toString();
	}

	public static @Nullable Surgery parseSurgery(final JsonObject m) {
		try {
			Surgery s = new Surgery(m.get("e").getAsInt(), m.get("implant").getAsString(), m.get("lo").getAsFloat(), m.get("hi").getAsFloat(),
				m.get("period").getAsInt(), m.get("timeout").getAsInt());
			boolean ok = s.implant().matches("[a-z_]{1,32}") && s.lo() >= 0.0F && s.hi() <= 1.0F && s.lo() < s.hi() && s.period() >= 200
				&& s.period() <= 10_000 && s.timeout() >= 1000 && s.timeout() <= 60_000;
			return ok ? s : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String act(final int e, final String a, final String arg) {
		JsonObject m = new JsonObject();
		m.addProperty("e", e);
		m.addProperty("a", a);
		m.addProperty("arg", arg);
		return m.toString();
	}

	/** A client's act, or null when it is malformed (the server drops it). */
	public static @Nullable Act parseAct(final JsonObject m) {
		if (!(m.get("e") instanceof JsonPrimitive e) || !e.isNumber() || !(m.get("a") instanceof JsonPrimitive a) || !a.isString()) {
			return null;
		}

		double id = e.getAsDouble();
		String arg = m.get("arg") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
		if (id != Math.rint(id) || Math.abs(id) > Integer.MAX_VALUE || !ACTS.contains(a.getAsString()) || !ARG.matcher(arg).matches()) {
			return null;
		}

		return new Act((int) id, a.getAsString(), arg);
	}
}
