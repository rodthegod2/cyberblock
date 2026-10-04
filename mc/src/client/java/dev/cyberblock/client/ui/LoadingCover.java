package dev.cyberblock.client.ui;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The timing and tip rules of the "Loading Night City..." cover (Q-U2): animated dots, and one tip line from the lang list
 * {@code cyberblock.loading.tip.1, .2, ...} that changes every few seconds. Pure functions plus one small clock, so a test covers them
 * without a game.
 */
final class LoadingCover {
	private static final long DOT_NANOS = 400_000_000L;
	private static final long TIP_NANOS = 6_000_000_000L;
	/** A gap this long between two frames of the cover means a new load: the tip starts over. */
	private static final long GAP_NANOS = 1_000_000_000L;
	private static final int MAX_TIPS = 64;
	private static long since;
	private static long last;

	private LoadingCover() {
	}

	/** 0 to 3 dots, one more every 0.4 s. */
	static int dots(final long nanos) {
		return (int) Math.floorMod(Math.floorDiv(nanos, DOT_NANOS), 4L);
	}

	/**
	 * When this load's cover began: the first frame, or the first one after a quiet spell. Client thread only. Call it once per drawn
	 * frame.
	 */
	static long since(final long now) {
		if (since == 0L || now - last > GAP_NANOS || now < last) {
			since = now;
		}

		last = now;
		return since;
	}

	/** The tip to show: a start that differs per load, the next one every 6 s. -1 when there are no tips. */
	static int tipIndex(final long since, final long now, final int count) {
		if (count <= 0) {
			return -1;
		}

		long start = Math.floorMod(since / 1_000_000L, (long) count);
		return (int) ((start + Math.max(0L, now - since) / TIP_NANOS) % count);
	}

	/** How many {@code cyberblock.loading.tip.<n>} lines the loaded language has (consecutive from 1). */
	static int tipCount(final Language language) {
		int count = 0;
		while (count < MAX_TIPS && language.has(tipKey(count + 1))) {
			count++;
		}

		return count;
	}

	static String tipKey(final int n) {
		return "cyberblock.loading.tip." + n;
	}

	/** The tip line for this moment, or null when the language has none. */
	static @Nullable Component tip(final long since, final long now) {
		Language language = Language.getInstance();
		int index = tipIndex(since, now, tipCount(language));
		return index < 0 ? null : Component.translatable(tipKey(index + 1));
	}
}
