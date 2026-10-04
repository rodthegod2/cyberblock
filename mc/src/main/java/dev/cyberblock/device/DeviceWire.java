package dev.cyberblock.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.net.Permissions.DeviceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Phase 4 device messages (megaplan section 1.9 and its Phase 0 amendment), pure: {@code devices} UP (at most 64 entries;
 * malformed or repeated entries are skipped), {@code devstate} UP, {@code devresult} UP and the {@code devact} DOWN.
 */
public final class DeviceWire {
	public static final int MAX_DEVICES = 64;
	public static final int CLS_CAP = 48;
	public static final int REASON_CAP = 128;
	/** World coordinates beyond this are no place a device can be. */
	private static final double XZ_LIMIT = 3.0e7, Y_LIMIT = 20_000.0;
	private static final Pattern DECIMAL_ID = Pattern.compile("\\d{1,20}");
	/** The acts Night City performs (section 1.9 amendment item 6). */
	public static final Set<String> NIGHT_CITY_ACTS = Set.of("open", "close", "toggle", "lock", "unlock", "on", "off", "disable", "enable", "distract",
		"friendly");
	private static final Set<String> BY = Set.of("npc", "host", "guest", "script");

	/** {@code devstate}: one device changed (by npc, host, guest or script). */
	public record State(long id, int st, int fl, String by) {
	}

	/** {@code devresult}: the answer to a devact request id. */
	public record Result(int id, boolean ok, String reason) {
	}

	/** The entries of a devices message and how many were skipped; null when it has no list at all. */
	public record Parsed(List<Device> devices, int rejected) {
	}

	private DeviceWire() {
	}

	/**
	 * {@code devices}: the entries, at most {@link #MAX_DEVICES}. An entry's type is its {@code kind} when that is one of the section
	 * 1.11 types, else what typeOfClass says for its class (device_map); an entry with neither is skipped.
	 */
	public static @Nullable Parsed devices(final JsonObject m, final Function<String, @Nullable DeviceType> typeOfClass) {
		if (!(m.get("list") instanceof JsonArray list)) {
			return null;
		}

		List<Device> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		int rejected = 0;
		for (JsonElement e : list) {
			Device d = out.size() < MAX_DEVICES ? device(e, typeOfClass) : null;
			if (d == null || !seen.add(d.id())) {
				rejected++;
			} else {
				out.add(d);
			}
		}

		return new Parsed(out, rejected);
	}

	private static @Nullable Device device(final JsonElement e, final Function<String, @Nullable DeviceType> typeOfClass) {
		if (!(e instanceof JsonObject o) || !(o.get("p") instanceof JsonArray p) || p.size() != 3) {
			return null;
		}

		Long id = id(o.get("id"));
		String cls = string(o.get("cls"), CLS_CAP);
		if (id == null || cls == null) {
			return null;
		}

		DeviceType type = o.get("kind") instanceof JsonPrimitive k && k.isString() ? DeviceType.byId(k.getAsString().toLowerCase(Locale.ROOT)) : null;
		if (type == null) {
			type = typeOfClass.apply(cls);
		}

		double x = number(p.get(0)), y = number(p.get(1)), z = number(p.get(2)), yaw = number(o.get("yaw"));
		Integer st = whole(o.get("st"), -2, 1), fl = whole(o.get("fl"), 0, 31);
		if (type == null || !(Math.abs(x) <= XZ_LIMIT) || !(Math.abs(z) <= XZ_LIMIT) || !(Math.abs(y) <= Y_LIMIT) || !Double.isFinite(yaw) || st == null
			|| fl == null) {
			return null;
		}

		return new Device(id, o.get("id").getAsJsonPrimitive().isString(), cls, type, x, y, z, (float) (yaw % 360.0), st, fl);
	}

	/** {@code devstate {id, st, fl, by}}, or null when malformed. by is one of npc, host, guest, script ("script" when absent). */
	public static @Nullable State state(final JsonObject m) {
		Long id = id(m.get("id"));
		Integer st = whole(m.get("st"), -2, 1), fl = whole(m.get("fl"), 0, 31);
		String by = m.has("by") ? string(m.get("by"), 16) : "script";
		if (id == null || st == null || fl == null || by == null || !BY.contains(by)) {
			return null;
		}

		return new State(id, st, fl, by);
	}

	/** {@code devresult {id, ok, reason}}, or null when malformed (reason absent = ""). */
	public static @Nullable Result result(final JsonObject m) {
		Integer id = whole(m.get("id"), Integer.MIN_VALUE, Integer.MAX_VALUE);
		JsonElement ok = m.get("ok");
		String reason = m.has("reason") ? string(m.get("reason"), REASON_CAP) : "";
		if (id == null || !(ok instanceof JsonPrimitive b) || !b.isBoolean() || reason == null) {
			return null;
		}

		return new Result(id, b.getAsBoolean(), reason);
	}

	/** {@code {"t":"devact","id":<request>,"dev":<device id, as the host sent it>,"act":"<act>"}}. */
	public static String devact(final int request, final Device d, final String act) {
		JsonObject o = new JsonObject();
		o.addProperty("t", "devact");
		o.addProperty("id", request);
		if (d.idString()) {
			o.addProperty("dev", d.idText());
		} else {
			o.addProperty("dev", d.id());
		}

		o.addProperty("act", act);
		return o.toString();
	}

	/** A device id: an unsigned decimal string (64 bits), or a whole JSON number from 0 to 2^53. */
	static @Nullable Long id(final @Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p)) {
			return null;
		}

		if (p.isString()) {
			if (!DECIMAL_ID.matcher(p.getAsString()).matches()) {
				return null;
			}

			try {
				return Long.parseUnsignedLong(p.getAsString());
			} catch (NumberFormatException tooBig) {
				return null;
			}
		}

		if (!p.isNumber()) {
			return null;
		}

		double v = p.getAsDouble();
		return v >= 0 && v <= 9.007199254740992e15 && v == Math.rint(v) ? (long) v : null;
	}

	private static @Nullable String string(final @Nullable JsonElement e, final int maxBytes) {
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			return null;
		}

		String s = p.getAsString();
		return s.getBytes(StandardCharsets.UTF_8).length <= maxBytes ? s : null;
	}

	private static double number(final @Nullable JsonElement e) {
		return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
	}

	private static @Nullable Integer whole(final @Nullable JsonElement e, final int min, final int max) {
		double v = number(e);
		return v >= min && v <= max && v == Math.rint(v) ? (int) v : null;
	}
}
