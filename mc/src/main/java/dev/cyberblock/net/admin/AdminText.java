package dev.cyberblock.net.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** The text of the server admin bits (plan task 2-M1): the startup line, refusals with `joinHelp`, `/cyberblock status`. */
public final class AdminText {
	/** `hs_ack.reason` is at most 256 characters (plan section 1.3); a refusal with `joinHelp` stays within it. */
	public static final int REASON_CAP = 256;

	/** One player in `/cyberblock status`; empty strings are left out. */
	record Row(String name, String role, String state, String versions, String rate) {}

	private AdminText() {}

	static String startupLine(final int protocol, final int maxHosts, final boolean requireMod, final boolean whitelist) {
		return "cyberblock server: ready (protocol " + protocol + ", maxHosts " + maxHosts + ", " + modRule(requireMod) + ", whitelist "
			+ onOff(whitelist) + ")";
	}

	/** A refusal reason with the server's `joinHelp` after it (once, within the cap); an empty reason is no refusal. */
	public static String withJoinHelp(final String reason, final String joinHelp) {
		if (reason == null || reason.isEmpty() || joinHelp == null || joinHelp.isBlank() || reason.contains(joinHelp.strip())) {
			return reason;
		}

		return cap(reason + " " + joinHelp.strip(), REASON_CAP);
	}

	static List<String> status(final List<Row> rows, final int maxHosts, final boolean requireMod, final boolean whitelist,
		final List<String> allowedHosts, final Map<String, Integer> content) {
		long hosts = rows.stream().filter(r -> r.role().equals("host")).count();
		List<String> lines = new ArrayList<>();
		lines.add("Cyberblock status: " + rows.size() + " online, " + hosts + (hosts == 1 ? " host" : " hosts") + " (max " + maxHosts + "), "
			+ modRule(requireMod) + ", whitelist " + onOff(whitelist));
		lines.add("hosts allowed: " + (allowedHosts.isEmpty() ? "any whitelisted player" : String.join(", ", allowedHosts)));
		for (Row r : rows) {
			StringBuilder line = new StringBuilder("- ").append(r.name()).append(": ").append(r.role());
			if (!r.state().isEmpty()) {
				line.append(" (").append(r.state()).append(')');
			}

			for (String part : new String[] {r.versions(), r.rate()}) {
				if (!part.isEmpty()) {
					line.append(", ").append(part);
				}
			}

			lines.add(line.toString());
		}

		StringJoiner kinds = new StringJoiner(", ");
		content.forEach((kind, n) -> kinds.add(kind + " " + n));
		lines.add("content: " + (content.isEmpty() ? "none loaded" : kinds));
		return lines;
	}

	/** At most {@code max} characters, never splitting a surrogate pair. */
	static String cap(final String s, final int max) {
		if (s.length() <= max) {
			return s;
		}

		int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
		return s.substring(0, end);
	}

	private static String modRule(final boolean requireMod) {
		return requireMod ? "requireMod" : "modOptional";
	}

	private static String onOff(final boolean on) {
		return on ? "on" : "OFF";
	}
}
