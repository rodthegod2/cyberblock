package dev.cyberblock.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.mode.overlord.Intensity;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.skin.SkinRules;
import org.jspecify.annotations.Nullable;

/**
 * The settings screen's server values (megaplan 9-S1): the client asks with {@code cyberblock:act} {@code t:"settings"} (an empty
 * object) and the server answers {@code cyberblock:ui} {@code t:"settings"} with this snapshot of what it decided. The screen shows it
 * and changes values only through {@code /cyberblock} commands, so the server decides (section 1.13). {@code world} (the server.json rows,
 * read-only) is sent to operators and the singleplayer owner only, and the join password appears in it only as on or off: the
 * secret is never part of the snapshot.
 */
public final class SettingsWire {
	public static final String T = "settings";

	/** server.json's rows as the screen shows them (section 1.13); hosts is the number of names on the list. */
	public record World(boolean allowHosts, int hosts, int maxHosts, boolean requireMod, double rate, int sanityRadius, boolean pvpDefault,
		boolean backupsDaily, int backupsKeep, int backupsHour, boolean webMap, boolean joinPassword, boolean joinHelp) {
	}

	/**
	 * One player's view: its guest toggles and device-type bits (section 1.6), its cyber skin body, PvP toggle and Holo-Table pin
	 * (section 1.13, per player), whether it has a host session and whether it may change server settings ({@code admin}: an operator
	 * or the singleplayer owner), the effective creative erase and whether server.json forces it, the difficulty, and the server.json
	 * rows (admins only, else null).
	 */
	public record Snapshot(int perms, int devices, String body, boolean pvp, boolean hidePin, boolean host, boolean admin, boolean erase,
		boolean eraseForced, Intensity overlord, int heat, @Nullable World world) {
		public Snapshot {
			perms &= Permissions.ALL;
			devices &= Permissions.ALL_DEVICES;
			body = SkinRules.FEMALE.equals(body) ? SkinRules.FEMALE : SkinRules.MALE;
			heat = Difficulty.heat(heat);
		}

		public Permissions permissions() {
			return new Permissions(this.perms, this.devices);
		}
	}

	private SettingsWire() {
	}

	public static String json(final Snapshot s) {
		JsonObject o = new JsonObject();
		o.addProperty("perms", s.perms());
		o.addProperty("devices", s.devices());
		o.addProperty("body", s.body());
		o.addProperty("pvp", s.pvp());
		o.addProperty("hidePin", s.hidePin());
		o.addProperty("host", s.host());
		o.addProperty("admin", s.admin());
		o.addProperty("erase", s.erase());
		o.addProperty("eraseForced", s.eraseForced());
		o.addProperty("overlord", s.overlord().key());
		o.addProperty("heat", s.heat());
		World w = s.world();
		if (w != null) {
			JsonObject world = new JsonObject();
			world.addProperty("allowHosts", w.allowHosts());
			world.addProperty("hosts", w.hosts());
			world.addProperty("maxHosts", w.maxHosts());
			world.addProperty("requireMod", w.requireMod());
			world.addProperty("rate", w.rate());
			world.addProperty("sanityRadius", w.sanityRadius());
			world.addProperty("pvpDefault", w.pvpDefault());
			JsonObject backups = new JsonObject();
			backups.addProperty("daily", w.backupsDaily());
			backups.addProperty("keep", w.backupsKeep());
			backups.addProperty("hour", w.backupsHour());
			world.add("backups", backups);
			world.addProperty("webMap", w.webMap());
			world.addProperty("joinPassword", w.joinPassword());
			world.addProperty("joinHelp", w.joinHelp());
			o.add("world", world);
		}

		return o.toString();
	}

	/** The snapshot, or null when the json is not an object. A missing or wrong-typed field reads as its default. */
	public static @Nullable Snapshot parse(final String json) {
		JsonObject o;
		try {
			JsonElement e = JsonParser.parseString(json);
			if (!e.isJsonObject()) {
				return null;
			}

			o = e.getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}

		World world = null;
		if (o.get("world") instanceof JsonObject w) {
			JsonObject b = w.get("backups") instanceof JsonObject bo ? bo : new JsonObject();
			world = new World(bool(w, "allowHosts", true), integer(w, "hosts", 0), integer(w, "maxHosts", 1), bool(w, "requireMod", true),
				number(w, "rate", 1.0), integer(w, "sanityRadius", 160), bool(w, "pvpDefault", false), bool(b, "daily", true), integer(b, "keep", 7),
				integer(b, "hour", 5), bool(w, "webMap", false), bool(w, "joinPassword", false), bool(w, "joinHelp", false));
		}

		Intensity overlord = Intensity.byKey(o.get("overlord") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "");
		return new Snapshot(integer(o, "perms", Permissions.DEFAULT.bits()), integer(o, "devices", Permissions.ALL_DEVICES),
			o.get("body") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : SkinRules.MALE, bool(o, "pvp", false), bool(o, "hidePin", false),
			bool(o, "host", false), bool(o, "admin", false), bool(o, "erase", false), bool(o, "eraseForced", false),
			overlord == null ? Difficulty.OVERLORD_DEFAULT : overlord, integer(o, "heat", Difficulty.HEAT_DEFAULT), world);
	}

	private static boolean bool(final JsonObject o, final String key, final boolean fallback) {
		return o.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
	}

	private static int integer(final JsonObject o, final String key, final int fallback) {
		return o.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : fallback;
	}

	private static double number(final JsonObject o, final String key, final double fallback) {
		return o.get(key) instanceof JsonPrimitive p && p.isNumber() && Double.isFinite(p.getAsDouble()) ? p.getAsDouble() : fallback;
	}
}
