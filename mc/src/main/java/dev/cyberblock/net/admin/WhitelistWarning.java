package dev.cyberblock.net.admin;

/**
 * When to repeat the whitelist warning (plan task 2-M1): at once, then every minute, while a dedicated server runs with
 * the whitelist off. Turning the whitelist on silences it; turning it off again warns at once. One thread only.
 */
final class WhitelistWarning {
	static final long EVERY_MS = 60_000;
	static final String TEXT = "*** CYBERBLOCK WARNING: the whitelist is OFF on this dedicated server. Anyone who knows the address can join."
		+ " Type: whitelist on   then: whitelist add <name> ***";

	private boolean warned;
	private long last;

	boolean due(final long nowMs, final boolean dedicated, final boolean whitelist) {
		if (!dedicated || whitelist) {
			warned = false;
			return false;
		}

		if (warned && nowMs - last < EVERY_MS) {
			return false;
		}

		warned = true;
		last = nowMs;
		return true;
	}
}
