package dev.cyberblock.client.net;

import java.util.List;
import java.util.Locale;

/**
 * Megaplan section 1.5 step 5: after the server connection drops (not a kick for the version or the whitelist) the client retries
 * after 5, 10, 20 and 40 s, then every 60 s, for 10 minutes from the drop.
 */
public final class Reconnect {
	static final int WINDOW_SECONDS = 600;
	private static final int[] FIRST = {5, 10, 20, 40};
	private static final int EVERY = 60;

	private Reconnect() {
	}

	/** Seconds to wait before retry number {@code attempt} (0 is the first retry). */
	public static int delaySeconds(final int attempt) {
		return attempt < FIRST.length ? FIRST[Math.max(attempt, 0)] : EVERY;
	}

	/** Seconds from the drop to retry number {@code attempt}. */
	public static int retryAt(final int attempt) {
		int at = 0;
		for (int i = 0; i <= attempt; i++) {
			at += delaySeconds(i);
		}

		return at;
	}

	/** Retry number {@code attempt} would fall outside the 10 minutes: give up and leave the disconnect screen. */
	public static boolean gaveUp(final int attempt) {
		return retryAt(attempt) > WINDOW_SECONDS;
	}

	/**
	 * Whether a disconnect screen's text is worth a retry: not when it carries one of the {@code refusals} (the version mismatch,
	 * the whitelist, a ban or kick, the missing modpack, the message flood; given in the player's language), compared ignoring case.
	 */
	public static boolean worthRetrying(final String screenText, final List<String> refusals) {
		String text = screenText.toLowerCase(Locale.ROOT);
		for (String refusal : refusals) {
			if (!refusal.isBlank() && text.contains(refusal.toLowerCase(Locale.ROOT))) {
				return false;
			}
		}

		return true;
	}
}
