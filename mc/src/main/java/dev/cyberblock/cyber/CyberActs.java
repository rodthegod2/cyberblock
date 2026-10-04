package dev.cyberblock.cyber;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The cyber track's `cyberblock:act` requests (client to server, megaplan section 1.3; task 1-C1), one JSON object each in the
 * payload's `json`, with the payload's `t`. Builders for the client, parsers for the server (null: the request breaks these
 * rules and is dropped). Everything a player could do through the integrated server before now goes this way, so a dedicated
 * server gets it too:
 * <ul>
 * <li>`scan` {"target":entityId|null,"eye":[x,y,z],"look":[x,y,z]}: the hack key is held; the server picks the proxy along
 * the ray (`target` is the client's own pick, a hint used only when the server's pick finds nothing)</li>
 * <li>`hack` {"hack":"short_circuit"}: the hack wheel fired this chip at the locked target</li>
 * <li>`perk` {"id":"iron_skin"}: a skill tree purchase</li>
 * <li>`ware` {"op":"install","id":"mantis_blades"} or {"op":"remove","slot":"arms/0"}: the cyberware screen</li>
 * <li>`fire` {"trigger":"os|arms|camo","aim":[x,y,z]|null}: a fire key (Y, L, O); `aim` is where the camera ray hits</li>
 * <li>`sandevistan` {}: the host double-tapped sprint</li>
 * </ul>
 */
public final class CyberActs {
	public static final String SCAN = "scan";
	public static final String HACK = "hack";
	public static final String PERK = "perk";
	public static final String WARE = "ware";
	public static final String FIRE = "fire";
	public static final String SANDEVISTAN = "sandevistan";
	private static final double XZ_LIMIT = 3.0E7;
	private static final double Y_LIMIT = 20000.0;
	private static final int MAX_TEXT = 32;

	/** A parsed `scan`: the ray from `eye` along the unit vector `look`, and the client's hint (null: none). */
	public record Scan(@Nullable Integer target, Vec3 eye, Vec3 look) {
	}

	/** A parsed `ware`: install `id`, or (install false) remove what is in slot key `slot`. */
	public record Ware(boolean install, @Nullable Cyberware cyberware, String slot) {
	}

	/** A parsed `fire`. */
	public record Fire(CyberwareRules.Trigger trigger, @Nullable Vec3 aim) {
	}

	private CyberActs() {
	}

	// ---- builders (client) --------------------------------------------------------------------------------------------

	public static String scan(final @Nullable Integer target, final Vec3 eye, final Vec3 look) {
		JsonObject m = new JsonObject();
		if (target == null) {
			m.add("target", JsonNull.INSTANCE);
		} else {
			m.addProperty("target", target);
		}

		m.add("eye", vec(eye));
		m.add("look", vec(look));
		return m.toString();
	}

	public static String hack(final Hack hack) {
		JsonObject m = new JsonObject();
		m.addProperty("hack", hack.wire());
		return m.toString();
	}

	public static String perk(final Perk perk) {
		JsonObject m = new JsonObject();
		m.addProperty("id", perk.id());
		return m.toString();
	}

	public static String install(final Cyberware cyberware) {
		JsonObject m = new JsonObject();
		m.addProperty("op", "install");
		m.addProperty("id", cyberware.id());
		return m.toString();
	}

	public static String remove(final String slotKey) {
		JsonObject m = new JsonObject();
		m.addProperty("op", "remove");
		m.addProperty("slot", slotKey);
		return m.toString();
	}

	public static String fire(final CyberwareRules.Trigger trigger, final @Nullable Vec3 aim) {
		JsonObject m = new JsonObject();
		m.addProperty("trigger", trigger.id());
		m.add("aim", aim == null ? JsonNull.INSTANCE : vec(aim));
		return m.toString();
	}

	private static JsonArray vec(final Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x);
		a.add(v.y);
		a.add(v.z);
		return a;
	}

	// ---- parsers (server) ---------------------------------------------------------------------------------------------

	public static @Nullable Scan parseScan(final JsonObject m) {
		try {
			JsonElement t = m.get("target");
			Integer target = t == null || t.isJsonNull() ? null : t.getAsInt();
			Vec3 eye = position(m.get("eye"));
			Vec3 look = direction(m.get("look"));
			return eye == null || look == null ? null : new Scan(target, eye, look);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Hack parseHack(final JsonObject m) {
		try {
			return Hack.byWire(m.get("hack").getAsString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Perk parsePerk(final JsonObject m) {
		try {
			return Perk.byId(m.get("id").getAsString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Ware parseWare(final JsonObject m) {
		try {
			String op = m.get("op").getAsString();
			if (op.equals("install")) {
				Cyberware c = Cyberware.byId(m.get("id").getAsString());
				return c == null ? null : new Ware(true, c, "");
			}

			String slot = m.get("slot").getAsString();
			return op.equals("remove") && !slot.isEmpty() && slot.length() <= MAX_TEXT ? new Ware(false, null, slot) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static @Nullable Fire parseFire(final JsonObject m) {
		try {
			String id = m.get("trigger").getAsString();
			for (CyberwareRules.Trigger trigger : CyberwareRules.Trigger.values()) {
				if (trigger.id().equals(id.toLowerCase(Locale.ROOT))) {
					JsonElement a = m.get("aim");
					if (a == null || a.isJsonNull()) {
						return new Fire(trigger, null);
					}

					Vec3 aim = position(a);
					return aim == null ? null : new Fire(trigger, aim);
				}
			}

			return null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** [x,y,z], finite and inside the world's limits, else null. */
	static @Nullable Vec3 position(final JsonElement e) {
		JsonArray a = e.getAsJsonArray();
		if (a.size() != 3) {
			return null;
		}

		double x = a.get(0).getAsDouble(), y = a.get(1).getAsDouble(), z = a.get(2).getAsDouble();
		return Math.abs(x) < XZ_LIMIT && Math.abs(y) < Y_LIMIT && Math.abs(z) < XZ_LIMIT ? new Vec3(x, y, z) : null;
	}

	/** [x,y,z] of a usable length (finite, not zero), normalised; else null. */
	static @Nullable Vec3 direction(final JsonElement e) {
		JsonArray a = e.getAsJsonArray();
		if (a.size() != 3) {
			return null;
		}

		Vec3 v = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
		double length = v.length();
		return length > 1.0E-6 && length < 1.0E6 ? v.scale(1.0 / length) : null;
	}
}
