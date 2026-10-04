package dev.cyberblock.net.admin;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Entries of `hosts` in config/cyberblock/server.json (plan section 1.13: player names or UUIDs). Only these two shapes are
 * accepted, so an op's typo or a quote can never reach the file.
 */
final class HostNames {
	/** A Minecraft player name: 3 to 16 ASCII letters, digits or underscores. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** A UUID in its dashed form (as the server's whitelist.json and usercache.json write it). */
	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private HostNames() {}

	/** The entry to store (a name as typed, a UUID in lower case), or null when it is neither. */
	static String normalize(final String raw) {
		if (raw == null) {
			return null;
		}

		String s = raw.strip();
		if (NAME.matcher(s).matches()) {
			return s;
		}

		return UUID.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : null;
	}

	/** Minecraft names are case-insensitive, and so are UUIDs. */
	static boolean same(final String a, final String b) {
		return a.strip().equalsIgnoreCase(b.strip());
	}
}
