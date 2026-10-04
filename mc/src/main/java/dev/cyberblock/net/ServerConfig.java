package dev.cyberblock.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Cyberblock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code config/cyberblock/server.json} (megaplan section 1.13): written with the defaults on first start; a value that is missing,
 * of the wrong type or out of range is logged and replaced by its default (the file itself is left for the admin to fix). Immutable;
 * {@link #get()} is the one the server started with.
 */
public record ServerConfig(boolean allowHosts, List<String> hosts, int maxHosts, boolean requireMod, String joinHelp, double rate,
	int sanityRadius, boolean creativeErasesReplica, boolean pvpDefault, Backups backups, boolean webMap) {
	public static final int SCHEMA = 1;
	/** Phase 1 runs one host; Phase 3 raises the default to 4 once its multi-host check passes. */
	public static final int DEFAULT_MAX_HOSTS = 1;
	static final int MAX_HOSTS_LIMIT = 16;
	static final int JOIN_HELP_CAP = 200;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public record Backups(boolean daily, int keep, int hour) {
		public static final Backups DEFAULT = new Backups(true, 7, 5);
	}

	public static final ServerConfig DEFAULT = new ServerConfig(true, List.of(), DEFAULT_MAX_HOSTS, true, "", 1.0, 160, false, false, Backups.DEFAULT, false);
	private static volatile ServerConfig current = DEFAULT;

	public ServerConfig {
		hosts = List.copyOf(hosts);
	}

	/** The config the server started with (DEFAULT before any start). */
	public static ServerConfig get() {
		return current;
	}

	/** Tests and the server start. */
	static void set(final ServerConfig config) {
		current = config;
	}

	/** The player is listed in "hosts" by name (any case) or by UUID. */
	public boolean listsHost(final String name, final UUID uuid) {
		for (String h : this.hosts) {
			if (h.equalsIgnoreCase(name) || h.equalsIgnoreCase(uuid.toString())) {
				return true;
			}
		}

		return false;
	}

	/** Server start: reads (or creates) the file and makes it current. Problems are logged, never fatal. */
	static ServerConfig load(final Path file) {
		ServerConfig config;
		if (!Files.exists(file)) {
			config = DEFAULT;
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, toJson(DEFAULT), StandardCharsets.UTF_8);
				Cyberblock.LOG.info("config: wrote the defaults to {}", file.getFileName());
			} catch (IOException e) {
				Cyberblock.LOG.warn("config: cannot write {}: {}", file, e.toString());
			}
		} else {
			JsonObject json;
			try {
				json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				Cyberblock.LOG.warn("config: {} is not a JSON object ({}); using the defaults", file.getFileName(), e.toString());
				json = new JsonObject();
			}

			config = parse(json, problem -> Cyberblock.LOG.warn("config: {}", problem));
		}

		set(config);
		return config;
	}

	/** Every field of the object, each validated on its own; a problem is reported and that field takes its default. */
	static ServerConfig parse(final JsonObject json, final Consumer<String> problems) {
		Reader r = new Reader(json, problems);
		if (json.has("schema") && r.integer("schema", SCHEMA, 1, 1) != SCHEMA) {
			problems.accept("schema must be " + SCHEMA);
		}

		JsonObject backupsJson = json.has("backups") && json.get("backups").isJsonObject() ? json.getAsJsonObject("backups") : new JsonObject();
		if (json.has("backups") && !json.get("backups").isJsonObject()) {
			problems.accept("backups must be an object; using the defaults");
		}

		Reader b = new Reader(backupsJson, p -> problems.accept("backups." + p));
		Backups backups = new Backups(b.bool("daily", Backups.DEFAULT.daily()), b.integer("keep", Backups.DEFAULT.keep(), 1, 365),
			b.integer("hour", Backups.DEFAULT.hour(), 0, 23));
		return new ServerConfig(
			r.bool("allowHosts", DEFAULT.allowHosts),
			r.hosts(),
			r.integer("maxHosts", DEFAULT.maxHosts, 1, MAX_HOSTS_LIMIT),
			r.bool("requireMod", DEFAULT.requireMod),
			r.string("joinHelp", DEFAULT.joinHelp, JOIN_HELP_CAP),
			r.number("rate", DEFAULT.rate, 0.1, 10.0),
			r.integer("sanityRadius", DEFAULT.sanityRadius, 16, 1024),
			r.bool("creativeErasesReplica", DEFAULT.creativeErasesReplica),
			r.bool("pvpDefault", DEFAULT.pvpDefault),
			backups,
			r.bool("webMap", DEFAULT.webMap));
	}

	static String toJson(final ServerConfig c) {
		JsonObject o = new JsonObject();
		o.addProperty("schema", SCHEMA);
		o.addProperty("allowHosts", c.allowHosts);
		JsonArray hosts = new JsonArray();
		c.hosts.forEach(hosts::add);
		o.add("hosts", hosts);
		o.addProperty("maxHosts", c.maxHosts);
		o.addProperty("requireMod", c.requireMod);
		o.addProperty("joinHelp", c.joinHelp);
		o.addProperty("rate", c.rate);
		o.addProperty("sanityRadius", c.sanityRadius);
		o.addProperty("creativeErasesReplica", c.creativeErasesReplica);
		o.addProperty("pvpDefault", c.pvpDefault);
		JsonObject backups = new JsonObject();
		backups.addProperty("daily", c.backups.daily());
		backups.addProperty("keep", c.backups.keep());
		backups.addProperty("hour", c.backups.hour());
		o.add("backups", backups);
		o.addProperty("webMap", c.webMap);
		return GSON.toJson(o) + System.lineSeparator();
	}

	/** Typed reads with defaults; each problem names the field and what was wrong. */
	private record Reader(JsonObject json, Consumer<String> problems) {
		private JsonPrimitive primitive(final String key) {
			JsonElement e = this.json.get(key);
			if (e == null) {
				this.problems.accept(key + " is missing; using the default");
				return null;
			}

			return e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
		}

		boolean bool(final String key, final boolean fallback) {
			if (!this.json.has(key)) {
				this.problems.accept(key + " is missing; using the default " + fallback);
				return fallback;
			}

			JsonPrimitive p = this.primitive(key);
			if (p == null || !p.isBoolean()) {
				this.problems.accept(key + " must be true or false; using the default " + fallback);
				return fallback;
			}

			return p.getAsBoolean();
		}

		double number(final String key, final double fallback, final double min, final double max) {
			if (!this.json.has(key)) {
				this.problems.accept(key + " is missing; using the default " + fallback);
				return fallback;
			}

			JsonPrimitive p = this.primitive(key);
			double v = p != null && p.isNumber() ? p.getAsDouble() : Double.NaN;
			if (!(v >= min && v <= max)) {
				this.problems.accept(String.format(Locale.ROOT, "%s must be a number from %s to %s; using the default %s", key, min, max, fallback));
				return fallback;
			}

			return v;
		}

		int integer(final String key, final int fallback, final int min, final int max) {
			if (!this.json.has(key)) {
				this.problems.accept(key + " is missing; using the default " + fallback);
				return fallback;
			}

			JsonPrimitive p = this.primitive(key);
			double v = p != null && p.isNumber() ? p.getAsDouble() : Double.NaN;
			if (!(v >= min && v <= max) || v != Math.rint(v)) {
				this.problems.accept(key + " must be a whole number from " + min + " to " + max + "; using the default " + fallback);
				return fallback;
			}

			return (int) v;
		}

		String string(final String key, final String fallback, final int maxBytes) {
			if (!this.json.has(key)) {
				this.problems.accept(key + " is missing; using the default");
				return fallback;
			}

			JsonPrimitive p = this.primitive(key);
			if (p == null || !p.isString() || !Payloads.fits(p.getAsString(), maxBytes)) {
				this.problems.accept(key + " must be a string of at most " + maxBytes + " bytes; using the default");
				return fallback;
			}

			return p.getAsString();
		}

		/** Player names (1-16 letters, digits, _) or UUIDs; a bad entry is skipped, a bad list is the default (empty). */
		List<String> hosts() {
			JsonElement e = this.json.get("hosts");
			if (e == null) {
				this.problems.accept("hosts is missing; using the default []");
				return List.of();
			}

			if (!e.isJsonArray()) {
				this.problems.accept("hosts must be a list of player names or UUIDs; using the default []");
				return List.of();
			}

			List<String> out = new ArrayList<>();
			for (JsonElement h : e.getAsJsonArray()) {
				String s = h.isJsonPrimitive() && h.getAsJsonPrimitive().isString() ? h.getAsString() : null;
				if (s != null && (NAME.matcher(s).matches() || isUuid(s))) {
					out.add(s);
				} else {
					this.problems.accept("hosts entry " + h + " is not a player name or UUID; skipped");
				}
			}

			return out;
		}

		private static boolean isUuid(final String s) {
			try {
				return UUID.fromString(s).toString().equalsIgnoreCase(s);
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
	}
}
