package dev.cyberblock.mode.van;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.hack.actions.ActWire;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Hacker in the Van's messages (megaplan task 8-V1), one JSON object each; parsers return null for anything that breaks the
 * rules (the caller drops it). Pure.
 * <ul>
 * <li>{@code cyberblock:act t:"van"} {@code {"op":"host","host":"<uuid>"}} (link the Van to that host), {@code {"op":"launch"}} (a drone
 * takes off and the camera goes to it), {@code {"op":"pilot","on":bool}} (the camera to the hovering drone, or back to the Van),
 * {@code {"op":"dock"}} (the drone comes home). The screen's photo feed is 6-I1's {@code feed} watch on the linked host (its frames come
 * as {@code cyberblock:ui t:"feed"}, the DLL's picture or the replica fallback).</li>
 * <li>{@code cyberblock:act t:"vhack"} {@code {"eid":n,"hack":"<quickhack>"}} (a chip on a person or a car's driver), {@code {"host":"<uuid>",
 * "dev":"<id>","act":"<devact>"}} (a device action) or {@code {"host":"<uuid>","dev":"<id>","hack":"device"}} (the lever chip on a
 * device). The car hacks and the area hacks go as 6-I1's {@code rhack} act.</li>
 * <li>{@code cyberblock:ui t:"van"}: the netrunner's state (below, {@link #state}).</li>
 * </ul>
 */
public final class VanWire {
	public static final String T_VAN = "van", T_VHACK = "vhack";
	public static final String UI_STATE = "van";
	public static final int NAME_MAX = 48;
	private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,20}");

	/** A van op from the client. */
	public sealed interface Op permits LinkHost, Launch, Pilot, Dock {
	}

	public record LinkHost(UUID host) implements Op {
	}

	public record Launch() implements Op {
	}

	public record Pilot(boolean on) implements Op {
	}

	public record Dock() implements Op {
	}

	/** A vhack request: a chip on a figure (eid), or an act or the lever chip on one host's device. */
	public sealed interface Request permits FigureHack, DeviceRequest {
	}

	public record FigureHack(int eid, Hack hack) implements Request {
	}

	/** {@code act} is null for the lever chip. */
	public record DeviceRequest(UUID host, long dev, @Nullable String act) implements Request {
	}

	/** A host the Van may link to: who, where V is, and whether its `hacks` toggle lets this netrunner in. */
	public record Host(UUID id, String name, Vec3 p, boolean open) {
	}

	/** One target on the screen: a figure (eid >= 0) or a device (dev != 0, its owner in host). */
	public record Target(VanRules.Kind kind, int eid, @Nullable UUID host, long dev, String name, Vec3 p, boolean hostile, String type, VanRules.Seen seen) {
	}

	/** The drone in the air: its entity, its charge 0..1 and how far it is from the Van; piloted while the camera is on it. */
	public record Drone(int eid, float battery, double range, boolean piloted) {
	}

	/**
	 * The netrunner's state: the Van (entity id, -1 when he left it), its linked host (null none) and V's place (the miniature's
	 * centre), the hosts in reach, the drone in the air (null when docked) with the dock's charge, and the targets he can see.
	 * `open` asks the client to open the Van screen.
	 */
	public record State(boolean open, int van, @Nullable UUID host, @Nullable Vec3 centre, List<Host> hosts, @Nullable Drone drone, float charge,
		List<Target> targets) {
	}

	private VanWire() {
	}

	// ---- acts (client builds, server parses) ------------------------------------------------------------------------------

	public static String linkHost(final UUID host) {
		JsonObject m = op("host");
		m.addProperty("host", host.toString());
		return m.toString();
	}

	public static String launch() {
		return op("launch").toString();
	}

	public static String pilot(final boolean on) {
		JsonObject m = op("pilot");
		m.addProperty("on", on);
		return m.toString();
	}

	public static String dock() {
		return op("dock").toString();
	}

	private static JsonObject op(final String op) {
		JsonObject m = new JsonObject();
		m.addProperty("op", op);
		return m;
	}

	public static @Nullable Op parseOp(final JsonObject m) {
		try {
			return switch (m.get("op").getAsString()) {
				case "host" -> {
					UUID host = uuid(m.get("host"));
					yield host == null ? null : new LinkHost(host);
				}
				case "launch" -> new Launch();
				case "pilot" -> bool(m.get("on")) instanceof Boolean on ? new Pilot(on) : null;
				case "dock" -> new Dock();
				default -> null;
			};
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String figureHack(final int eid, final Hack hack) {
		JsonObject m = new JsonObject();
		m.addProperty("eid", eid);
		m.addProperty("hack", hack.wire());
		return m.toString();
	}

	public static String deviceAct(final UUID host, final long dev, final String act) {
		JsonObject m = device(host, dev);
		m.addProperty("act", act);
		return m.toString();
	}

	public static String deviceChip(final UUID host, final long dev) {
		JsonObject m = device(host, dev);
		m.addProperty("hack", Hack.DEVICE.wire());
		return m.toString();
	}

	private static JsonObject device(final UUID host, final long dev) {
		JsonObject m = new JsonObject();
		m.addProperty("host", host.toString());
		m.addProperty("dev", Long.toUnsignedString(dev));
		return m;
	}

	/**
	 * A vhack request, or null: a figure needs a non-negative eid and a quickhack that is not the device lever; a device needs its host,
	 * a non-zero id and either an act (a lower-case word) or the lever chip, never both.
	 */
	public static @Nullable Request parseRequest(final JsonObject m) {
		try {
			if (m.has("eid")) {
				int eid = m.get("eid").getAsInt();
				Hack hack = Hack.byWire(m.get("hack").getAsString());
				return eid >= 0 && hack != null && !hack.needsDevice() && !m.has("dev") ? new FigureHack(eid, hack) : null;
			}

			UUID host = uuid(m.get("host"));
			long dev = id(m.get("dev"));
			if (host == null || dev == 0L || m.has("act") == m.has("hack")) {
				return null;
			}

			if (m.has("hack")) {
				return Hack.DEVICE.wire().equals(m.get("hack").getAsString()) ? new DeviceRequest(host, dev, null) : null;
			}

			String act = m.get("act").getAsString();
			return act.matches("[a-z]{1,16}") ? new DeviceRequest(host, dev, act) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- ui (server builds, client parses) --------------------------------------------------------------------------------

	public static String state(final State s) {
		JsonObject m = new JsonObject();
		m.addProperty("open", s.open());
		m.addProperty("van", s.van());
		m.addProperty("host", s.host() == null ? "" : s.host().toString());
		if (s.centre() != null) {
			m.add("centre", vec(s.centre()));
		}

		JsonArray hosts = new JsonArray();
		for (Host h : s.hosts()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", h.id().toString());
			o.addProperty("name", cap(h.name()));
			o.add("p", vec(h.p()));
			o.addProperty("open", h.open());
			hosts.add(o);
		}

		m.add("hosts", hosts);
		if (s.drone() != null) {
			JsonObject d = new JsonObject();
			d.addProperty("eid", s.drone().eid());
			d.addProperty("battery", round(s.drone().battery()));
			d.addProperty("range", round(s.drone().range()));
			d.addProperty("piloted", s.drone().piloted());
			m.add("drone", d);
		}

		m.addProperty("charge", round(s.charge()));
		JsonArray targets = new JsonArray();
		for (Target t : s.targets().subList(0, Math.min(s.targets().size(), VanRules.MAX_TARGETS))) {
			JsonObject o = new JsonObject();
			o.addProperty("k", t.kind().wire());
			if (t.kind() == VanRules.Kind.DEVICE) {
				o.addProperty("host", t.host() == null ? "" : t.host().toString());
				o.addProperty("dev", Long.toUnsignedString(t.dev()));
				o.addProperty("type", t.type());
			} else {
				o.addProperty("eid", t.eid());
			}

			o.addProperty("name", cap(t.name()));
			o.add("p", vec(t.p()));
			o.addProperty("hostile", t.hostile());
			o.addProperty("seen", t.seen().wire());
			targets.add(o);
		}

		m.add("targets", targets);
		return m.toString();
	}

	/** The state, or null when it breaks the shape (a bad target is skipped, not fatal). */
	public static @Nullable State parseState(final JsonObject m) {
		try {
			List<Host> hosts = new ArrayList<>();
			for (JsonElement e : m.getAsJsonArray("hosts")) {
				JsonObject o = e.getAsJsonObject();
				UUID id = uuid(o.get("id"));
				Vec3 p = ActWire.position(o.get("p"));
				if (id != null && p != null) {
					hosts.add(new Host(id, cap(o.get("name").getAsString()), p, o.get("open").getAsBoolean()));
				}
			}

			Drone drone = null;
			if (m.get("drone") instanceof JsonObject d) {
				drone = new Drone(d.get("eid").getAsInt(), Math.clamp(d.get("battery").getAsFloat(), 0.0F, 1.0F), d.get("range").getAsDouble(),
					d.get("piloted").getAsBoolean());
			}

			List<Target> targets = new ArrayList<>();
			for (JsonElement e : m.getAsJsonArray("targets")) {
				Target t = target(e.getAsJsonObject());
				if (t != null && targets.size() < VanRules.MAX_TARGETS) {
					targets.add(t);
				}
			}

			String host = m.get("host").getAsString();
			return new State(m.get("open").getAsBoolean(), m.get("van").getAsInt(), host.isEmpty() ? null : uuid(m.get("host")),
				m.has("centre") ? ActWire.position(m.get("centre")) : null, hosts, drone, Math.clamp(m.get("charge").getAsFloat(), 0.0F, 1.0F), targets);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Target target(final JsonObject o) {
		try {
			VanRules.Kind kind = VanRules.Kind.byWire(o.get("k").getAsString());
			Vec3 p = ActWire.position(o.get("p"));
			VanRules.Seen seen = "drone".equals(o.get("seen").getAsString()) ? VanRules.Seen.DRONE : VanRules.Seen.SCREEN;
			if (p == null) {
				return null;
			}

			String name = cap(o.get("name").getAsString());
			boolean hostile = o.get("hostile").getAsBoolean();
			if (kind == VanRules.Kind.DEVICE) {
				UUID host = uuid(o.get("host"));
				long dev = id(o.get("dev"));
				String type = o.get("type").getAsString();
				return host == null || dev == 0L ? null : new Target(kind, -1, host, dev, name, p, hostile, type.length() > 16 ? "" : type, seen);
			}

			int eid = o.get("eid").getAsInt();
			return eid < 0 ? null : new Target(kind, eid, null, 0L, name, p, hostile, "", seen);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- helpers -------------------------------------------------------------------------------------------------------

	static JsonArray vec(final Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(round(v.x));
		a.add(round(v.y));
		a.add(round(v.z));
		return a;
	}

	/** Two decimals are plenty on a screen and keep the state small. */
	static double round(final double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	static String cap(final String s) {
		return s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
	}

	static @Nullable Boolean bool(final @Nullable JsonElement e) {
		return e instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
	}

	static @Nullable UUID uuid(final @Nullable JsonElement e) {
		try {
			return e instanceof JsonPrimitive p && p.isString() ? UUID.fromString(p.getAsString()) : null;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** A Night City id as the protocol writes it (unsigned decimal string), 0 when absent or bad. */
	static long id(final @Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isString() || !DECIMAL.matcher(p.getAsString()).matches()) {
			return 0L;
		}

		try {
			return Long.parseUnsignedLong(p.getAsString());
		} catch (NumberFormatException tooBig) {
			return 0L;
		}
	}

	/** The device type's id for the screen ("door", "camera", ...). */
	static String typeId(final Enum<?> type) {
		return type.name().toLowerCase(Locale.ROOT);
	}
}
