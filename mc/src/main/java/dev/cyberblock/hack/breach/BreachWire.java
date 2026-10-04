package dev.cyberblock.hack.breach;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Breach screen's payloads (protocol.md "Phase 6: payloads"), both sides; parsing never trusts the other side.
 * <ul>
 * <li>{@code cyberblock:ui t:"breach"} (server to client), on open, after every pick (a refused one too) and at the end: {@code {id, dev,
 * size, grid:[codes], buffer, picks:[cells], seqs:[{id, codes, ok}], total, left, state, strength}}: the puzzle id, the breached device's
 * decimal id ({@code "0"} for a breach with no device: a practice or a Phase 8 player hack), {@code size} 5..7 (4 for Phase 8's short
 * breach), the grid row by row as hex codes, the timer in seconds and the seconds left (it runs from the opening), {@code state} one of
 * {@link #STATES}, {@code strength} 0 until the end. The planted solution never leaves the server.</li>
 * <li>{@code cyberblock:act t:"breach"} (client to server): {@code {op:"pick", id, cell}} or {@code {op:"abort", id}}.</li>
 * </ul>
 */
public final class BreachWire {
	/** The cyberblock:ui and cyberblock:act type. */
	public static final String T = "breach";
	public static final String RUN = "run", SOLVED = "solved";
	/** protocol.md "Breach states". */
	public static final Set<String> STATES = Set.of(RUN, SOLVED, "failed", "timeout", "aborted");
	private static final Pattern DAEMON_ID = Pattern.compile("[a-z0-9_]{1,32}");
	private static final Pattern DEV = Pattern.compile("0|[1-9][0-9]{0,19}");

	/** One sequence on the screen: its daemon id, its codes and whether it is uploaded. */
	public record Seq(String id, int[] codes, boolean ok) {
	}

	/** The screen data: everything the screen shows. */
	public record Screen(int id, String dev, int size, int[] grid, int buffer, int[] picks, List<Seq> seqs, int total, double left, String state,
		int strength) {
		public boolean done() {
			return !this.state.equals(RUN);
		}
	}

	/** A player's request: a pick (cell >= 0) or an abort (cell -1). */
	public record Request(int id, boolean abort, int cell) {
	}

	private BreachWire() {
	}

	/** A daemon's name: {@code cyberblock.daemon.<id>} (6A's title_key for its defaults), the id in words as the fallback. */
	public static Component daemonName(final String id) {
		StringBuilder words = new StringBuilder();
		for (String w : id.split("_")) {
			if (!w.isEmpty()) {
				words.append(words.isEmpty() ? "" : " ").append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
			}
		}

		return Component.translatableWithFallback("cyberblock.daemon." + id, words.toString());
	}

	/** The screen data for this breach now. */
	public static String screen(final int id, final String dev, final BreachGame g, final long nowMs) {
		return screen(id, dev, g, nowMs, false);
	}

	/**
	 * The screen data for this breach now; {@code kept}: the player closed this screen of a device breach before a pick, so it shows
	 * {@code aborted} while the server keeps the puzzle (and its timer) for the next right-click, which opens it under a new id.
	 */
	public static String screen(final int id, final String dev, final BreachGame g, final long nowMs, final boolean kept) {
		BreachPuzzle p = g.puzzle();
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("dev", dev);
		o.addProperty("size", p.size());
		o.add("grid", codes(p.grid()));
		o.addProperty("buffer", p.buffer());
		JsonArray picks = new JsonArray();
		for (int cell : g.picks()) {
			picks.add(cell);
		}

		o.add("picks", picks);
		JsonArray seqs = new JsonArray();
		boolean[] up = g.uploaded();
		for (int i = 0; i < p.daemons().size(); i++) {
			JsonObject s = new JsonObject();
			s.addProperty("id", p.daemons().get(i).id());
			s.add("codes", codes(p.daemons().get(i).seq()));
			s.addProperty("ok", up[i]);
			seqs.add(s);
		}

		o.add("seqs", seqs);
		o.addProperty("total", p.seconds());
		o.addProperty("left", Math.round(g.leftMs(nowMs) / 100.0) / 10.0);
		BreachResults.Outcome end = g.ended() ? BreachResults.Outcome.of(g) : null;
		o.addProperty("state", end != null ? end.state() : kept ? "aborted" : RUN);
		o.addProperty("strength", end == null ? 0 : end.strength());
		return o.toString();
	}

	public static String pick(final int id, final int cell) {
		JsonObject o = new JsonObject();
		o.addProperty("op", "pick");
		o.addProperty("id", id);
		o.addProperty("cell", cell);
		return o.toString();
	}

	public static String abort(final int id) {
		JsonObject o = new JsonObject();
		o.addProperty("op", "abort");
		o.addProperty("id", id);
		return o.toString();
	}

	private static JsonArray codes(final int[] codes) {
		JsonArray a = new JsonArray();
		for (int c : codes) {
			a.add(BreachPuzzle.CODES.get(c));
		}

		return a;
	}

	// ---- parsing ----------------------------------------------------------------------------------------------------------------

	/** A player's {@code cyberblock:act breach}, or null. The server still checks the id and the pick against the game. */
	public static @Nullable Request request(final JsonObject m) {
		Integer id = whole(m.get("id"), 0, Integer.MAX_VALUE);
		String op = text(m.get("op"), 8);
		if (id == null || op == null) {
			return null;
		}

		if (op.equals("abort")) {
			return new Request(id, true, -1);
		}

		Integer cell = whole(m.get("cell"), 0, 48);
		return op.equals("pick") && cell != null ? new Request(id, false, cell) : null;
	}

	/** The screen data, or null when anything is out of shape (a 4x4 short breach is accepted for Phase 8). */
	public static @Nullable Screen parse(final JsonObject m) {
		Integer id = whole(m.get("id"), 0, Integer.MAX_VALUE), size = whole(m.get("size"), 4, 7), buffer = whole(m.get("buffer"), 4, 8),
			total = whole(m.get("total"), 1, 300), strength = whole(m.get("strength"), 0, 3);
		String dev = text(m.get("dev"), 20), state = text(m.get("state"), 8);
		JsonElement left = m.get("left");
		if (id == null || size == null || buffer == null || total == null || strength == null || dev == null || !DEV.matcher(dev).matches()
			|| state == null || !STATES.contains(state) || left == null || !left.isJsonPrimitive() || !left.getAsJsonPrimitive().isNumber()
			|| !(left.getAsDouble() >= 0 && left.getAsDouble() <= total)) {
			return null;
		}

		int[] grid = codes(m.get("grid"), size * size, size * size);
		int[] picks = cells(m.get("picks"), buffer, size * size);
		if (grid == null || picks == null || !(m.get("seqs") instanceof JsonArray a) || a.isEmpty() || a.size() > 3) {
			return null;
		}

		List<Seq> seqs = new ArrayList<>();
		for (JsonElement e : a) {
			String sid = e instanceof JsonObject s ? text(s.get("id"), 32) : null;
			int[] codes = sid == null ? null : codes(e.getAsJsonObject().get("codes"), 2, buffer);
			JsonElement ok = sid == null ? null : e.getAsJsonObject().get("ok");
			if (sid == null || !DAEMON_ID.matcher(sid).matches() || codes == null || ok == null || !ok.isJsonPrimitive() || !ok.getAsJsonPrimitive().isBoolean()) {
				return null;
			}

			seqs.add(new Seq(sid, codes, ok.getAsBoolean()));
		}

		return new Screen(id, dev, size, grid, buffer, picks, List.copyOf(seqs), total, left.getAsDouble(), state, strength);
	}

	/** Hex codes ("1C", ...) as code indexes, between min and max of them, or null. */
	private static int @Nullable [] codes(final @Nullable JsonElement e, final int min, final int max) {
		if (!(e instanceof JsonArray a) || a.size() < min || a.size() > max) {
			return null;
		}

		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			String code = text(a.get(i), 2);
			out[i] = code == null ? -1 : BreachPuzzle.CODES.indexOf(code);
			if (out[i] < 0) {
				return null;
			}
		}

		return out;
	}

	/** At most max distinct cells below cells, or null. */
	private static int @Nullable [] cells(final @Nullable JsonElement e, final int max, final int cells) {
		if (!(e instanceof JsonArray a) || a.size() > max) {
			return null;
		}

		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			Integer cell = whole(a.get(i), 0, cells - 1);
			if (cell == null) {
				return null;
			}

			out[i] = cell;
		}

		return Arrays.stream(out).distinct().count() == out.length ? out : null;
	}

	private static @Nullable Integer whole(final @Nullable JsonElement e, final int min, final int max) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return null;
		}

		double v = e.getAsDouble();
		return v >= min && v <= max && v == Math.rint(v) ? (int) v : null;
	}

	private static @Nullable String text(final @Nullable JsonElement e, final int maxChars) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().length() <= maxChars ? e.getAsString() : null;
	}
}
