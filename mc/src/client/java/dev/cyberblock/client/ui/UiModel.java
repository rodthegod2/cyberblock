package dev.cyberblock.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * The Night City UI data Cyberpunk streams (plan 2026-10-03-retest3-polish section 1.1): one immutable snapshot per message
 * type, replaced whole by UiLink on the link thread and read by the HUD and screens on the render thread.
 *
 * <p>Validation (section 1.1): a field of the wrong type, a number out of its range or a missing required field makes the
 * message invalid (the parser returns null and the caller counts it); inside a list such a fault drops only that entry, and
 * entries past a list's cap are dropped. Strings are cut to their limit in code points (a valid DLL string, at most that many
 * UTF-8 bytes, is never cut) and control characters are stripped. Unknown kinds become {@code other} for pins and quests,
 * unknown flags are dropped, an unknown {@code notify.kind} becomes {@code info}; an unknown {@code screen} name, call phase or
 * reply kind makes the message invalid.
 */
public final class UiModel {
	public static final Set<String> QUEST_KINDS = Set.of("main", "side", "gig", "other");
	public static final Set<String> PIN_KINDS = Set.of("quest", "vendor", "fasttravel", "gig", "police", "ripperdoc", "apartment", "car", "other");
	public static final Set<String> CALL_PHASES = Set.of("incoming", "start", "active", "end");
	public static final Set<String> NOTIFY_KINDS = Set.of("quest", "item", "money", "xp", "level", "sms", "shard", "zone", "wanted", "warning", "save", "info");
	public static final Set<String> OPEN_NAMES = Set.of("pause", "map", "journal", "phone", "inventory", "skills", "cyberware");
	/** Section 1.11 pass-through names, in the order the plan lists them. */
	public static final List<String> PASS_NAMES = List.of("vendor", "ripperdoc", "stash", "wardrobe", "crafting", "breach", "arcade", "braindance", "tutorial",
		"settings", "saves", "death", "loading", "other");
	public static final Set<String> REPLY_KINDS = Set.of("journal", "phone", "atlas");
	public static final Set<String> UIACT_NAMES = Set.of("save", "loadlast", "mainmenu", "cpmenu", "track", "smsreply", "fasttravel", "journalreq", "phonereq", "atlasreq");
	private static final long UINT32_MAX = 0xFFFF_FFFFL;
	/** Positions beyond these are not a place in the world (HostState uses the same limits for the camera). */
	private static final double XZ_LIMIT = 2.9E7;
	private static final double Y_LIMIT = 20000.0;

	/** Messages and entries dropped as invalid since start (any thread). */
	public static final AtomicInteger bad = new AtomicInteger();

	// ---- the live snapshots (written by UiLink on the link thread, read anywhere) ----
	public static volatile @Nullable Stats stats;
	public static volatile @Nullable Quest quest;
	public static volatile Pins pins = Pins.EMPTY;
	public static volatile Prompt prompt = Prompt.EMPTY;
	public static volatile Choices choices = Choices.EMPTY;
	public static volatile @Nullable Subtitle subtitle;
	/** The pass-through menu Cyberpunk shows (a section 1.11 name), "" when none. */
	public static volatile String pass = "";
	public static volatile List<Npc> npcs = List.of();

	private UiModel() {
	}

	/** A new host session ({@code hello}) or Cyberblock off ({@code state active:false}): nothing Cyberpunk said before holds. */
	public static void reset() {
		stats = null;
		quest = null;
		pins = Pins.EMPTY;
		prompt = Prompt.EMPTY;
		choices = Choices.EMPTY;
		subtitle = null;
		pass = "";
		npcs = List.of();
	}

	// ---- records ----

	public record Pos(double x, double y, double z) {
	}

	/** @param speed km/h, -1 on foot */
	public record Stats(long money, int wanted, String district, int hour, int minute, double speed) {
		public boolean inVehicle() {
			return this.speed >= 0.0;
		}
	}

	/** @param tot 0 = no counter */
	public record Objective(String text, int cur, int tot, boolean optional, boolean on) {
	}

	public record Quest(String title, String kind, List<Objective> objectives, @Nullable Pos wp) {
		public boolean isEmpty() {
			return this.title.isEmpty() && this.objectives.isEmpty();
		}
	}

	public record Pin(Pos p, String kind, boolean tracked) {
	}

	public record Pins(List<Pin> list) {
		public static final Pins EMPTY = new Pins(List.of());
	}

	/** @param key the key label, at most 3 characters ("F", "R", "1") */
	public record PromptEntry(String text, String key, boolean hold, boolean inactive) {
	}

	public record Prompt(String title, List<PromptEntry> list) {
		public static final Prompt EMPTY = new Prompt("", List.of());

		public boolean isEmpty() {
			return this.list.isEmpty();
		}
	}

	public enum Flag {
		INACTIVE, PAY, QUEST, CHECK_OK, CHECK_FAIL, TIMED;

		static @Nullable Flag of(final String name) {
			for (Flag f : values()) {
				if (f.name().toLowerCase(Locale.ROOT).equals(name)) {
					return f;
				}
			}

			return null;
		}
	}

	public record Choice(String text, Set<Flag> flags) {
	}

	public record Hub(int id, String title, List<Choice> list) {
	}

	/**
	 * @param sel the selected entry of the active hub, -1 none
	 * @param left seconds left when the message arrived, -1 untimed
	 * @param at System.nanoTime() when it arrived
	 */
	public record Choices(List<Hub> hubs, int active, int sel, double left, long at) {
		public static final Choices EMPTY = new Choices(List.of(), 0, -1, -1.0, 0L);

		public boolean isEmpty() {
			return this.hubs.isEmpty();
		}

		public boolean timed() {
			return this.left >= 0.0;
		}

		public @Nullable Hub activeHub() {
			for (Hub hub : this.hubs) {
				if (hub.id() == this.active) {
					return hub;
				}
			}

			return null;
		}
	}

	/** @param at System.nanoTime() when it arrived */
	public record Subtitle(boolean show, String key, String who, String text, double secs, long at) {
	}

	public record Call(String phase, String who, boolean audio, boolean reject) {
	}

	public record Notify(String kind, String title, String text, double secs) {
	}

	/** Either {@code open} (a Minecraft screen to open) or {@code pass} (a pass-through name, "" = ended) is set. */
	public record ScreenMsg(@Nullable String open, @Nullable String pass) {
	}

	/** @param state journal: 0 active, 1 done, 2 failed; phone: 0 from the contact, 1 from V, 2 a reply V can choose */
	public record Line(long hash, String text, int state) {
	}

	public record Group(long hash, String title, String kind, boolean tracked, boolean done, List<Line> lines) {
	}

	/** @param index the fast-travel index, -1 when the pin is not one */
	public record ReplyPin(Pos p, String kind, String name, int index) {
	}

	public record Reply(String kind, int id, List<Group> groups, List<ReplyPin> pins) {
	}

	/** An NPC or vehicle around V (from {@code npcs}), for the radar. */
	public record Npc(Pos p, boolean vehicle, boolean hostile) {
	}

	/** Thrown inside the parsers for a fault; the parser turns it into null (message) or a dropped entry. */
	private static final class Invalid extends RuntimeException {
		Invalid(final String what) {
			super(what, null, false, false);
		}
	}

	// ---- parsers ----

	public static @Nullable Stats parseStats(final JsonObject m) {
		try {
			JsonArray time = array(m, "time");
			if (time.size() != 2) {
				throw new Invalid("time");
			}

			double speed = number(m.get("speed"), -1.0, 10000.0);
			if (speed < 0.0 && speed != -1.0) {
				throw new Invalid("speed");
			}

			return new Stats(integer(m.get("money"), 0, Integer.MAX_VALUE), (int) integer(m.get("wanted"), 0, 5), string(m, "district", 64, true),
				(int) integer(time.get(0), 0, 23), (int) integer(time.get(1), 0, 59), speed);
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Quest parseQuest(final JsonObject m) {
		try {
			String title = string(m, "title", 128, true);
			String kind = m.has("kind") ? oneOf(string(m, "kind", 16, true), QUEST_KINDS, "other") : "other";
			List<Objective> objectives = new ArrayList<>();
			for (JsonElement e : array(m, "obj")) {
				if (objectives.size() >= 12) {
					bad.incrementAndGet();
					break;
				}

				try {
					JsonObject o = object(e);
					objectives.add(new Objective(string(o, "text", 256, true), (int) integer(o.get("cur"), 0, Integer.MAX_VALUE),
						(int) integer(o.get("tot"), 0, Integer.MAX_VALUE), flag(o, "opt"), flag(o, "on")));
				} catch (Invalid | IllegalStateException x) {
					bad.incrementAndGet();
				}
			}

			JsonElement wp = m.get("wp");
			return new Quest(title, kind, List.copyOf(objectives), wp == null || wp instanceof JsonNull ? null : pos(wp));
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Pins parsePins(final JsonObject m) {
		try {
			List<Pin> list = new ArrayList<>();
			for (JsonElement e : array(m, "list")) {
				if (list.size() >= 32) {
					bad.incrementAndGet();
					break;
				}

				try {
					JsonObject o = object(e);
					list.add(new Pin(pos(o.get("p")), oneOf(string(o, "kind", 16, true), PIN_KINDS, "other"), flag(o, "tr")));
				} catch (Invalid | IllegalStateException x) {
					bad.incrementAndGet();
				}
			}

			return new Pins(List.copyOf(list));
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Prompt parsePrompt(final JsonObject m) {
		try {
			String title = string(m, "title", 128, false);
			List<PromptEntry> list = new ArrayList<>();
			for (JsonElement e : array(m, "list")) {
				if (list.size() >= 6) {
					bad.incrementAndGet();
					break;
				}

				try {
					JsonObject o = object(e);
					list.add(new PromptEntry(string(o, "text", 128, true), string(o, "key", 3, false), flag(o, "hold"), flag(o, "inactive")));
				} catch (Invalid | IllegalStateException x) {
					bad.incrementAndGet();
				}
			}

			return new Prompt(title, List.copyOf(list));
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Choices parseChoices(final JsonObject m, final long now) {
		try {
			int active = (int) integer(m.get("active"), Integer.MIN_VALUE, Integer.MAX_VALUE);
			int sel = (int) integer(m.get("sel"), -1, Integer.MAX_VALUE);
			double left = number(m.get("left"), -1.0, 3600.0);
			if (left < 0.0 && left != -1.0) {
				throw new Invalid("left");
			}

			List<Hub> hubs = new ArrayList<>();
			int entries = 0;
			for (JsonElement e : array(m, "hubs")) {
				if (hubs.size() >= 4 || entries >= 12) {
					bad.incrementAndGet();
					break;
				}

				try {
					JsonObject o = object(e);
					int id = (int) integer(o.get("id"), Integer.MIN_VALUE, Integer.MAX_VALUE);
					String title = string(o, "title", 128, false);
					List<Choice> list = new ArrayList<>();
					for (JsonElement c : array(o, "list")) {
						if (entries >= 12) {
							bad.incrementAndGet();
							break;
						}

						try {
							JsonObject co = object(c);
							String text = string(co, "text", 256, true);
							Set<Flag> flags = EnumSet.noneOf(Flag.class);
							if (co.has("f")) {
								for (JsonElement f : array(co, "f")) {
									Flag flag = f instanceof JsonPrimitive p && p.isString() ? Flag.of(p.getAsString()) : null;
									if (flag != null) {
										flags.add(flag);
									}
								}
							}

							list.add(new Choice(text, Set.copyOf(flags)));
							entries++;
						} catch (Invalid | IllegalStateException x) {
							bad.incrementAndGet();
						}
					}

					hubs.add(new Hub(id, title, List.copyOf(list)));
				} catch (Invalid | IllegalStateException x) {
					bad.incrementAndGet();
				}
			}

			return new Choices(List.copyOf(hubs), active, sel, left, now);
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Subtitle parseSub(final JsonObject m, final long now) {
		try {
			if (!(m.get("show") instanceof JsonPrimitive p && p.isBoolean())) {
				throw new Invalid("show");
			}

			return new Subtitle(p.getAsBoolean(), string(m, "key", 64, false), string(m, "who", 64, false), string(m, "text", 256, false),
				m.has("secs") ? number(m.get("secs"), 0.0, 30.0) : 0.0, now);
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Call parseCall(final JsonObject m) {
		try {
			String phase = string(m, "phase", 16, true);
			if (!CALL_PHASES.contains(phase)) {
				throw new Invalid("phase");
			}

			return new Call(phase, string(m, "who", 64, false), flag(m, "audio"), flag(m, "reject"));
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Notify parseNotify(final JsonObject m) {
		try {
			return new Notify(oneOf(string(m, "kind", 16, true), NOTIFY_KINDS, "info"), string(m, "title", 128, false), string(m, "text", 256, false),
				m.has("secs") ? number(m.get("secs"), 0.0, 30.0) : 0.0);
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable ScreenMsg parseScreen(final JsonObject m) {
		try {
			if (m.has("open")) {
				String open = string(m, "open", 16, true);
				return OPEN_NAMES.contains(open) ? new ScreenMsg(open, null) : null;
			}

			if (m.has("pass")) {
				String pass = string(m, "pass", 16, true);
				return pass.isEmpty() || PASS_NAMES.contains(pass) ? new ScreenMsg(null, pass) : null;
			}

			return null;
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	public static @Nullable Reply parseReply(final JsonObject m) {
		try {
			String kind = string(m, "kind", 16, true);
			if (!REPLY_KINDS.contains(kind)) {
				throw new Invalid("kind");
			}

			int id = (int) integer(m.get("id"), Integer.MIN_VALUE, Integer.MAX_VALUE);
			List<Group> groups = new ArrayList<>();
			int lines = 0;
			if (m.has("groups")) {
				for (JsonElement e : array(m, "groups")) {
					if (groups.size() >= 128) {
						bad.incrementAndGet();
						break;
					}

					try {
						JsonObject o = object(e);
						long hash = integer(o.get("hash"), 0, UINT32_MAX);
						String title = string(o, "title", 128, false);
						String groupKind = string(o, "kind", 16, false);
						groupKind = kind.equals("journal") ? oneOf(groupKind, QUEST_KINDS, "other") : groupKind;
						boolean tracked = flag(o, "tracked"), done = flag(o, "done");
						List<Line> list = new ArrayList<>();
						if (o.has("lines")) {
							for (JsonElement l : array(o, "lines")) {
								if (lines >= 512) {
									bad.incrementAndGet();
									break;
								}

								try {
									JsonObject lo = object(l);
									list.add(new Line(integer(lo.get("hash"), 0, UINT32_MAX), string(lo, "text", 256, false), (int) integer(lo.get("state"), 0, 2)));
									lines++;
								} catch (Invalid | IllegalStateException x) {
									bad.incrementAndGet();
								}
							}
						}

						groups.add(new Group(hash, title, groupKind, tracked, done, List.copyOf(list)));
					} catch (Invalid | IllegalStateException x) {
						bad.incrementAndGet();
					}
				}
			}

			List<ReplyPin> pins = new ArrayList<>();
			if (m.has("pins")) {
				for (JsonElement e : array(m, "pins")) {
					if (pins.size() >= 256) {
						bad.incrementAndGet();
						break;
					}

					try {
						JsonObject o = object(e);
						pins.add(new ReplyPin(pos(o.get("p")), oneOf(string(o, "kind", 16, true), PIN_KINDS, "other"), string(o, "name", 64, false),
							o.has("i") ? (int) integer(o.get("i"), -1, Integer.MAX_VALUE) : -1));
					} catch (Invalid | IllegalStateException x) {
						bad.incrementAndGet();
					}
				}
			}

			return new Reply(kind, id, List.copyOf(groups), List.copyOf(pins));
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	/** {@code npcs} (layer 2): the radar only needs where, what and whether hostile. Null without a list. */
	public static @Nullable List<Npc> parseNpcs(final JsonObject m) {
		try {
			List<Npc> list = new ArrayList<>();
			for (JsonElement e : array(m, "list")) {
				if (list.size() >= 48) {
					break;
				}

				try {
					JsonObject o = object(e);
					String kind = string(o, "kind", 16, true);
					list.add(new Npc(pos(o.get("p")), kind.equals("vehicle"), flag(o, "hostile")));
				} catch (Invalid | IllegalStateException x) {
					bad.incrementAndGet();
				}
			}

			return List.copyOf(list);
		} catch (Invalid | IllegalStateException e) {
			return null;
		}
	}

	// ---- Minecraft to Cyberpunk ----

	/**
	 * {"t":"uiact","a":a,"arg":arg,"hash":hash,"id":id} (section 1.1): a Night City screen button. {@code arg} is cut to 64 code
	 * points; an unknown action or a hash outside Uint32 is a programming error.
	 */
	public static String uiact(final String a, final String arg, final long hash, final int id) {
		if (!UIACT_NAMES.contains(a)) {
			throw new IllegalArgumentException("unknown uiact " + a);
		}

		if (hash < 0 || hash > UINT32_MAX) {
			throw new IllegalArgumentException("hash out of Uint32: " + hash);
		}

		JsonObject m = new JsonObject();
		m.addProperty("t", "uiact");
		m.addProperty("a", a);
		m.addProperty("arg", clean(arg, 64));
		m.addProperty("hash", hash);
		m.addProperty("id", id);
		return m.toString();
	}

	// ---- helpers ----

	/** Control characters stripped, then cut to at most {@code limit} code points (never inside a surrogate pair). */
	public static String clean(final String s, final int limit) {
		StringBuilder out = new StringBuilder(Math.min(s.length(), limit * 2));
		int count = 0;
		for (int i = 0; i < s.length() && count < limit; ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (!Character.isISOControl(cp)) {
				out.appendCodePoint(cp);
				count++;
			}
		}

		return out.toString();
	}

	private static String string(final JsonObject o, final String name, final int limit, final boolean required) {
		JsonElement e = o.get(name);
		if (e == null || e instanceof JsonNull) {
			if (required) {
				throw new Invalid(name);
			}

			return "";
		}

		if (!(e instanceof JsonPrimitive p && p.isString())) {
			throw new Invalid(name);
		}

		return clean(p.getAsString(), limit);
	}

	private static boolean flag(final JsonObject o, final String name) {
		JsonElement e = o.get(name);
		if (e == null) {
			return false;
		}

		if (!(e instanceof JsonPrimitive p && p.isBoolean())) {
			throw new Invalid(name);
		}

		return p.getAsBoolean();
	}

	/** A finite number within min..max (Gson's lenient parser lets NaN and Infinity through as numbers). */
	private static double number(final @Nullable JsonElement e, final double min, final double max) {
		if (!(e instanceof JsonPrimitive p && p.isNumber())) {
			throw new Invalid("number");
		}

		double v = p.getAsDouble();
		if (!(v >= min && v <= max)) {
			throw new Invalid("range");
		}

		return v;
	}

	private static long integer(final @Nullable JsonElement e, final long min, final long max) {
		double v = number(e, min, max);
		if (v != Math.rint(v)) {
			throw new Invalid("integer");
		}

		return (long) v;
	}

	private static JsonArray array(final JsonObject o, final String name) {
		if (!(o.get(name) instanceof JsonArray a)) {
			throw new Invalid(name);
		}

		return a;
	}

	private static JsonObject object(final JsonElement e) {
		if (!(e instanceof JsonObject o)) {
			throw new Invalid("entry");
		}

		return o;
	}

	private static Pos pos(final @Nullable JsonElement e) {
		if (!(e instanceof JsonArray a) || a.size() != 3) {
			throw new Invalid("position");
		}

		return new Pos(number(a.get(0), -XZ_LIMIT, XZ_LIMIT), number(a.get(1), -Y_LIMIT, Y_LIMIT), number(a.get(2), -XZ_LIMIT, XZ_LIMIT));
	}

	private static String oneOf(final String value, final Set<String> allowed, final String fallback) {
		return allowed.contains(value) ? value : fallback;
	}
}
