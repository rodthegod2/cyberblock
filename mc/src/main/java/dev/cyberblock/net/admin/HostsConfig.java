package dev.cyberblock.net.admin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the admin values of config/cyberblock/server.json (plan section 1.13) and edits its `hosts` list for
 * `/cyberblock hosts add|remove`. An edit keeps every other key as it is, never overwrites a file it cannot parse, and
 * replaces the file in one move (a crash mid-write leaves the old file).
 */
final class HostsConfig {
	enum Outcome { ADDED, REMOVED, ALREADY, ABSENT, BAD_NAME, BAD_FILE, IO_ERROR }

	/** {@code name} is the normalized entry (null for BAD_NAME); {@code hosts} the list after the edit. */
	record Result(Outcome outcome, String name, List<String> hosts) {}

	/** The values the admin bits use, with the section 1.13 defaults for anything missing or bad. */
	record Settings(int maxHosts, boolean requireMod, String joinHelp, List<String> hosts) {}

	static final Settings DEFAULTS = new Settings(1, true, "", List.of());

	private HostsConfig() {}

	static synchronized Result edit(final Path file, final String raw, final boolean add) {
		String name = HostNames.normalize(raw);
		if (name == null) {
			return new Result(Outcome.BAD_NAME, null, List.of());
		}

		JsonObject root;
		try {
			if (Files.exists(file)) {
				JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				if (!(parsed instanceof JsonObject o)) {
					return new Result(Outcome.BAD_FILE, name, List.of());
				}

				root = o;
			} else if (add) {
				root = new JsonObject();
				root.addProperty("schema", 1);
			} else {
				return new Result(Outcome.ABSENT, name, List.of());
			}
		} catch (JsonParseException e) {
			return new Result(Outcome.BAD_FILE, name, List.of());
		} catch (IOException e) {
			return new Result(Outcome.IO_ERROR, name, List.of());
		}

		JsonArray hosts;
		if (!root.has("hosts")) {
			hosts = new JsonArray();
			root.add("hosts", hosts);
		} else if (root.get("hosts") instanceof JsonArray a && a.asList().stream().allMatch(HostsConfig::isString)) {
			hosts = a;
		} else {
			return new Result(Outcome.BAD_FILE, name, List.of());
		}

		int found = -1;
		for (int i = 0; i < hosts.size() && found < 0; i++) {
			if (HostNames.same(hosts.get(i).getAsString(), name)) {
				found = i;
			}
		}

		if (add && found >= 0) {
			return new Result(Outcome.ALREADY, hosts.get(found).getAsString(), strings(hosts));
		}

		if (!add && found < 0) {
			return new Result(Outcome.ABSENT, name, strings(hosts));
		}

		if (add) {
			hosts.add(name);
		} else {
			// the entry as it was stored, for the op's message
			name = hosts.remove(found).getAsString();
		}

		try {
			write(file, root);
		} catch (IOException e) {
			return new Result(Outcome.IO_ERROR, name, List.of());
		}

		return new Result(add ? Outcome.ADDED : Outcome.REMOVED, name, strings(hosts));
	}

	/** Never throws: a missing or broken file gives the defaults; a bad value gives that value's default. */
	static Settings read(final Path file) {
		JsonObject o;
		try {
			if (!Files.exists(file) || !(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)) instanceof JsonObject parsed)) {
				return DEFAULTS;
			}

			o = parsed;
		} catch (IOException | JsonParseException e) {
			return DEFAULTS;
		}

		int maxHosts = o.get("maxHosts") instanceof JsonPrimitive p && p.isNumber() && p.getAsDouble() >= 1 && p.getAsDouble() <= Integer.MAX_VALUE
			? p.getAsInt() : DEFAULTS.maxHosts();
		boolean requireMod = o.get("requireMod") instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : DEFAULTS.requireMod();
		String joinHelp = isString(o.get("joinHelp")) ? AdminText.cap(o.get("joinHelp").getAsString().strip(), AdminText.REASON_CAP) : DEFAULTS.joinHelp();
		List<String> hosts = new ArrayList<>();
		if (o.get("hosts") instanceof JsonArray a) {
			for (JsonElement e : a) {
				String entry = isString(e) ? HostNames.normalize(e.getAsString()) : null;
				if (entry != null) {
					hosts.add(entry);
				}
			}
		}

		return new Settings(maxHosts, requireMod, joinHelp, List.copyOf(hosts));
	}

	private static boolean isString(final JsonElement e) {
		return e instanceof JsonPrimitive p && p.isString();
	}

	private static List<String> strings(final JsonArray a) {
		return a.asList().stream().map(JsonElement::getAsString).toList();
	}

	private static void write(final Path file, final JsonObject root) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, "server.json.", ".tmp");
		try {
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n", StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
