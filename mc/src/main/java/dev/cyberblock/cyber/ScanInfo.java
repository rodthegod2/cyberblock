package dev.cyberblock.cyber;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {"t":"scaninfo","id","ok","target":"<id>","name","faction","threat","hp","device","p":[x,y,z],"size":[w,h]}: Cyberpunk's
 * reply to a `scan` (plan section 1.2), positions already in Minecraft convention.
 *
 * @param target the Cyberpunk entity id (0: nothing)
 * @param x the feet or device base
 * @param w width and h height in metres
 */
public record ScanInfo(int id, boolean ok, long target, String name, String faction, String threat, float hp, boolean device,
	double x, double y, double z, float w, float h) {
	public static final Set<String> THREATS = Set.of("none", "low", "medium", "high", "extreme");
	private static final int MAX_TEXT = 64;
	private static final double MAX_COORD = 3.0E7;
	/** Nothing Cyberpunk scans is bigger than a building's sign or a truck. */
	private static final double MAX_SIZE = 64.0;
	/** Nothing found (or nothing the player may scan). */
	public static final ScanInfo NOTHING = new ScanInfo(0, false, 0L, "", "", "none", 0.0F, false, 0.0, 0.0, 0.0, 0.0F, 0.0F);

	/** The reply, or null when anything in it breaks the protocol's limits (the caller drops and counts it). */
	public static @Nullable ScanInfo parse(final JsonObject m) {
		try {
			int id = m.get("id").getAsInt();
			if (id < 0 || !m.get("ok").isJsonPrimitive() || !m.getAsJsonPrimitive("ok").isBoolean()) {
				return null;
			}

			boolean ok = m.get("ok").getAsBoolean();
			if (!ok) {
				return new ScanInfo(id, false, 0L, "", "", "none", 0.0F, false, 0.0, 0.0, 0.0, 0.0F, 0.0F);
			}

			long target = entityId(m.get("target"));
			String name = text(m.get("name"));
			String faction = text(m.get("faction"));
			String threat = m.get("threat").getAsString();
			double hp = m.get("hp").getAsDouble();
			JsonArray p = m.getAsJsonArray("p");
			JsonArray size = m.getAsJsonArray("size");
			if (target == 0L || name == null || faction == null || !THREATS.contains(threat) || !(hp >= 0.0 && hp <= 1.0)
				|| p.size() != 3 || size.size() != 2) {
				return null;
			}

			double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
			double w = size.get(0).getAsDouble(), h = size.get(1).getAsDouble();
			if (!coord(x) || !coord(y) || !coord(z) || !(w >= 0.0 && w <= MAX_SIZE) || !(h >= 0.0 && h <= MAX_SIZE)) {
				return null;
			}

			boolean device = m.has("device") && m.get("device").getAsBoolean();
			return new ScanInfo(id, true, target, name, faction, threat, (float) hp, device, x, y, z, (float) w, (float) h);
		} catch (RuntimeException e) {
			// missing field, wrong type, unparsable id: all the same bad message
			return null;
		}
	}

	/**
	 * The answer as the server hands it to the asker's client (`cyberblock:ui t:"scaninfo"`, megaplan task 1-C1): the same
	 * fields, rebuilt from the checked values, plus `eid`, the Minecraft entity id of the proxy the scan picked (-1: none).
	 */
	public String toJson(final int eid) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "scaninfo");
		m.addProperty("id", this.id);
		m.addProperty("ok", this.ok);
		if (this.ok) {
			m.addProperty("target", Long.toUnsignedString(this.target));
			m.addProperty("name", this.name);
			m.addProperty("faction", this.faction);
			m.addProperty("threat", this.threat);
			m.addProperty("hp", this.hp);
			m.addProperty("device", this.device);
			JsonArray p = new JsonArray();
			p.add(this.x);
			p.add(this.y);
			p.add(this.z);
			m.add("p", p);
			JsonArray size = new JsonArray();
			size.add(this.w);
			size.add(this.h);
			m.add("size", size);
		}

		m.addProperty("eid", eid);
		return m.toString();
	}

	/** A Cyberpunk entity id: the EntityID hash as an unsigned 64-bit decimal string. */
	static long entityId(final JsonElement e) {
		String s = e.getAsString();
		if (s.isEmpty() || s.length() > 20 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
			throw new IllegalArgumentException("bad id " + s);
		}

		return Long.parseUnsignedLong(s);
	}

	private static @Nullable String text(final JsonElement e) {
		String s = e.getAsString();
		return s.length() <= MAX_TEXT ? s : null;
	}

	private static boolean coord(final double v) {
		return v > -MAX_COORD && v < MAX_COORD;
	}
}
