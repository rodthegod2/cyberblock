package dev.cyberblock.hack.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The interaction map's messages (megaplan task 6-I1), one JSON object each. Parsers return null for a request that breaks its
 * rules (the caller drops it); builders are what the server sends. Pure.
 * <ul>
 * <li>`cyberblock:act t:"rhack"` {"hack":"veh_lock","eid":n} (a car figure's entity id), {"hack":"cam_feed","host":"&lt;uuid&gt;",
 * "dev":"&lt;id&gt;"}, {"hack":"blackout","host":"&lt;uuid&gt;","secs":120}, {"hack":"traffic_switch","host":"&lt;uuid&gt;"} or
 * {"hack":"gridlock|outline|ping_area","p":[x,y,z]}</li>
 * <li>`rhack` DOWN, exactly the shapes of shared/protocol.md "Phase 6: Minecraft → Cyberpunk": a vehicle hack
 * {"t":"rhack","id":n,"target":"&lt;id&gt;","hack":"...","secs":30}, `cam_feed` {"t","id","target","hack"} and an area hack
 * {"t","id","target":null,"hack","p":[x,y,z],"r":m,"secs":s} (`r` and `secs` whole numbers; the district hacks are centred on V)</li>
 * <li>`cyberblock:act t:"comms"` {"to":"&lt;uuid&gt;","text":"...","p":[x,y,z]}: a short message and/or a waypoint for a host</li>
 * <li>`cyberblock:act t:"netmap"` {}: the Net Map's state, answered by `cyberblock:ui t:"netmap"`</li>
 * <li>`cyberblock:act t:"feed"` {"host":"&lt;uuid&gt;","on":bool} and `t:"feedmap"` {"host":"&lt;uuid&gt;"}: watch a host's photo
 * feed, or take its map item</li>
 * <li>`feed` UP {"t":"feed","w":128,"h":128,"px":"&lt;base64 of 16384 map colour bytes&gt;"} and `feedreq` DOWN {"t":"feedreq","on":bool}</li>
 * </ul>
 */
public final class ActWire {
	public static final String T_RHACK = "rhack", T_COMMS = "comms", T_NETMAP = "netmap", T_FEED = "feed", T_FEEDMAP = "feedmap";
	/** cyberblock:ui types the server sends for these screens. */
	public static final String UI_RESULT = "ract", UI_NETMAP = "netmap", UI_FEED = "feed", UI_VPANEL = "vpanel", UI_CAMFEED = "camfeed",
		UI_OUTLINE = "outline", UI_PING = "netping", UI_COMMS = "comms";
	/** A Comms message: at most this many characters. */
	public static final int MAX_TEXT = 64;
	/** The feed picture: 128 x 128 map colour bytes. */
	public static final int FEED_SIDE = 128, FEED_BYTES = FEED_SIDE * FEED_SIDE;
	/** Map colour ids are 0..63 (MapColor has 64 slots); the low two bits are the brightness. */
	static final int MAP_COLOURS = 64;
	private static final double XZ_LIMIT = 3.0E7, Y_LIMIT = 20000.0;

	/** A parsed `rhack` request: `eid` -1 and `dev` 0 when absent, `host` and `p` null when absent, `secs` the blackout's length. */
	public record Request(RemoteHack hack, int eid, @Nullable UUID host, long dev, @Nullable Vec3 p, int secs) {
	}

	/** A parsed `comms`: to whom, the text ("" none) and the waypoint (null none); at least one of them. */
	public record Comms(UUID to, String text, int @Nullable [] p) {
	}

	/** A parsed `feed` watch request. */
	public record FeedWatch(UUID host, boolean on) {
	}

	private ActWire() {
	}

	// ---- requests (client builds, server parses) ------------------------------------------------------------------------

	public static String vehicle(final RemoteHack hack, final int eid) {
		JsonObject m = hack(hack);
		m.addProperty("eid", eid);
		return m.toString();
	}

	public static String camera(final UUID host, final long dev) {
		JsonObject m = hack(RemoteHack.CAM_FEED);
		m.addProperty("host", host.toString());
		m.addProperty("dev", Long.toUnsignedString(dev));
		return m.toString();
	}

	public static String district(final RemoteHack hack, final UUID host, final int secs) {
		JsonObject m = hack(hack);
		m.addProperty("host", host.toString());
		if (hack == RemoteHack.BLACKOUT) {
			m.addProperty("secs", secs);
		}

		return m.toString();
	}

	public static String point(final RemoteHack hack, final Vec3 p) {
		JsonObject m = hack(hack);
		m.add("p", vec(p));
		return m.toString();
	}

	private static JsonObject hack(final RemoteHack hack) {
		JsonObject m = new JsonObject();
		m.addProperty("hack", hack.wire());
		return m;
	}

	/** The `rhack` request, or null when it breaks the rules of its hack's target. */
	public static @Nullable Request parse(final JsonObject m) {
		try {
			RemoteHack hack = RemoteHack.byWire(m.get("hack").getAsString());
			if (hack == null) {
				return null;
			}

			return switch (hack.target()) {
				case VEHICLE -> {
					int eid = m.get("eid").getAsInt();
					yield eid >= 0 ? new Request(hack, eid, null, 0L, null, hack.seconds()) : null;
				}
				case CAMERA -> {
					UUID host = uuid(m.get("host"));
					long dev = id(m.get("dev"));
					yield host != null && dev != 0L ? new Request(hack, -1, host, dev, null, 0) : null;
				}
				case DISTRICT -> {
					UUID host = uuid(m.get("host"));
					int secs = hack == RemoteHack.BLACKOUT && m.has("secs") ? m.get("secs").getAsInt() : hack.seconds();
					boolean ok = host != null && (hack != RemoteHack.BLACKOUT || secs >= RemoteHack.MIN_BLACKOUT && secs <= RemoteHack.MAX_BLACKOUT);
					yield ok ? new Request(hack, -1, host, 0L, null, secs) : null;
				}
				case POINT -> {
					Vec3 p = position(m.get("p"));
					yield p != null ? new Request(hack, -1, null, 0L, p, hack.seconds()) : null;
				}
			};
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String comms(final UUID to, final String text, final int @Nullable [] p) {
		JsonObject m = new JsonObject();
		m.addProperty("to", to.toString());
		m.addProperty("text", text);
		if (p != null) {
			JsonArray a = new JsonArray();
			for (int c : p) {
				a.add(c);
			}

			m.add("p", a);
		}

		return m.toString();
	}

	/** The Comms request, or null: an unknown `to`, a text over 64 characters, a bad waypoint, or neither text nor waypoint. */
	public static @Nullable Comms parseComms(final JsonObject m) {
		try {
			UUID to = uuid(m.get("to"));
			String text = m.has("text") ? clean(m.get("text").getAsString()) : "";
			int[] p = null;
			if (m.has("p") && !m.get("p").isJsonNull()) {
				Vec3 v = position(m.get("p"));
				if (v == null) {
					return null;
				}

				p = new int[] {(int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z)};
			}

			if (to == null || text.codePointCount(0, text.length()) > MAX_TEXT || text.isEmpty() && p == null) {
				return null;
			}

			return new Comms(to, text, p);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Control characters and formatting codes out, surrounding spaces trimmed (a message is shown as plain text). */
	static String clean(final String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints().filter(c -> !Character.isISOControl(c) && c != '§').forEach(out::appendCodePoint);
		return out.toString().strip();
	}

	public static String feedWatch(final UUID host, final boolean on) {
		JsonObject m = new JsonObject();
		m.addProperty("host", host.toString());
		m.addProperty("on", on);
		return m.toString();
	}

	public static @Nullable FeedWatch parseFeedWatch(final JsonObject m) {
		try {
			UUID host = uuid(m.get("host"));
			JsonElement on = m.get("on");
			return host != null && on.isJsonPrimitive() && on.getAsJsonPrimitive().isBoolean() ? new FeedWatch(host, on.getAsBoolean()) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String host(final UUID host) {
		JsonObject m = new JsonObject();
		m.addProperty("host", host.toString());
		return m.toString();
	}

	public static @Nullable UUID parseHost(final JsonObject m) {
		try {
			return uuid(m.get("host"));
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- DOWN (server to the owner's Cyberpunk) ---------------------------------------------------------------------------

	/**
	 * The DOWN `rhack` (shared/protocol.md "Phase 6: Minecraft → Cyberpunk" and its validation rules): a vehicle hack carries the car's
	 * id and `secs`, `cam_feed` the camera's id and nothing else, an area hack `target` null and always `p` (its centre: the point, or
	 * V for the district hacks), `r` and `secs` as whole numbers. `secs` is {@link RemoteHack#downSeconds}.
	 */
	public static String rhack(final int id, final long target, final RemoteHack hack, final @Nullable Vec3 p, final int secs) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "rhack");
		m.addProperty("id", id);
		if (hack.area()) {
			if (p == null) {
				throw new IllegalArgumentException(hack.wire() + " is an area hack: it needs its centre");
			}

			m.add("target", JsonNull.INSTANCE);
			m.addProperty("hack", hack.wire());
			m.add("p", vec(p));
			m.addProperty("r", hack.radius());
			m.addProperty("secs", secs);
			return m.toString();
		}

		m.addProperty("target", Long.toUnsignedString(target));
		m.addProperty("hack", hack.wire());
		if (hack.target() == RemoteHack.Target.VEHICLE) {
			m.addProperty("secs", secs);
		}

		return m.toString();
	}

	public static String feedReq(final boolean on) {
		return "{\"t\":\"feedreq\",\"on\":" + on + "}";
	}

	/** The `feed` UP's picture: 16384 map colour bytes, or null when the message breaks the rules (size, base64, colour ids). */
	public static byte @Nullable [] parseFeed(final JsonObject m) {
		try {
			if (m.get("w").getAsInt() != FEED_SIDE || m.get("h").getAsInt() != FEED_SIDE) {
				return null;
			}

			byte[] px = Base64.getDecoder().decode(m.get("px").getAsString());
			if (px.length != FEED_BYTES) {
				return null;
			}

			for (byte b : px) {
				if ((b & 0xFF) >> 2 >= MAP_COLOURS) {
					return null;
				}
			}

			return px;
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- cyberblock:ui (server to a player) --------------------------------------------------------------------------------

	/** {"hack":"veh_lock","ok":bool,"reason":"..."}: the answer to a request (or the server's own refusal). */
	public static String result(final RemoteHack hack, final boolean ok, final String reason) {
		JsonObject m = new JsonObject();
		m.addProperty("hack", hack.wire());
		m.addProperty("ok", ok);
		m.addProperty("reason", reason);
		return m.toString();
	}

	/** {"eid":n,"name":"..."}: open the Vehicle Panel on this car figure. */
	public static String vpanel(final int eid, final String name) {
		JsonObject m = new JsonObject();
		m.addProperty("eid", eid);
		m.addProperty("name", name);
		return m.toString();
	}

	/** {"p":[x,y,z],"yaw":deg,"name":"..."}: a camera's place and facing for the replica view. */
	public static String camFeed(final Vec3 p, final float yaw, final String name) {
		JsonObject m = new JsonObject();
		m.add("p", vec(p));
		m.addProperty("yaw", yaw);
		m.addProperty("name", name);
		return m.toString();
	}

	/** {"ids":[entity ids],"secs":s}: outline these figures. */
	public static String outline(final Collection<Integer> ids, final int secs) {
		JsonObject m = new JsonObject();
		JsonArray a = new JsonArray();
		ids.forEach(a::add);
		m.add("ids", a);
		m.addProperty("secs", secs);
		return m.toString();
	}

	/** {"p":[x,y,z],"secs":s,"by":"name"}: a ping marker. */
	public static String ping(final Vec3 p, final int secs, final String by) {
		JsonObject m = new JsonObject();
		m.add("p", vec(p));
		m.addProperty("secs", secs);
		m.addProperty("by", by);
		return m.toString();
	}

	/** {"from":"name","text":"...","p":[x,y,z]}: a Comms message for its host. */
	public static String commsUi(final String from, final String text, final int @Nullable [] p) {
		JsonObject m = new JsonObject();
		m.addProperty("from", from);
		m.addProperty("text", text);
		if (p != null) {
			JsonArray a = new JsonArray();
			for (int c : p) {
				a.add(c);
			}

			m.add("p", a);
		}

		return m.toString();
	}

	/** {"host":"&lt;uuid&gt;","px":"&lt;base64&gt;"}: one photo feed frame for a Net Map viewer. */
	public static String feedUi(final UUID host, final byte[] px) {
		JsonObject m = new JsonObject();
		m.addProperty("host", host.toString());
		m.addProperty("px", Base64.getEncoder().encodeToString(px));
		return m.toString();
	}

	// ---- helpers -------------------------------------------------------------------------------------------------------

	static JsonArray vec(final Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x);
		a.add(v.y);
		a.add(v.z);
		return a;
	}

	/** [x,y,z], finite and inside the world's limits, else null. */
	public static @Nullable Vec3 position(final @Nullable JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			return null;
		}

		JsonArray a = e.getAsJsonArray();
		double x = a.get(0).getAsDouble(), y = a.get(1).getAsDouble(), z = a.get(2).getAsDouble();
		return Math.abs(x) < XZ_LIMIT && Math.abs(y) < Y_LIMIT && Math.abs(z) < XZ_LIMIT ? new Vec3(x, y, z) : null;
	}

	static @Nullable UUID uuid(final @Nullable JsonElement e) {
		try {
			return e == null || !e.isJsonPrimitive() ? null : UUID.fromString(e.getAsString());
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** A Night City id as the protocol writes it (unsigned decimal), 0 when absent or bad. */
	static long id(final @Nullable JsonElement e) {
		try {
			return e == null || !e.isJsonPrimitive() ? 0L : Long.parseUnsignedLong(e.getAsString());
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}
}
