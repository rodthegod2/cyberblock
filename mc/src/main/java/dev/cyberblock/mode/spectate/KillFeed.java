package dev.cyberblock.mode.spectate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The caster overlay's kill feed (megaplan 8-T3): the newest {@link #CAP} events of the modes (a player killed, downed, revived,
 * bled out, picked up by Trauma Team), each as "who, what, whom" and the server tick it happened. Other phases push their own
 * (match kills, captures) through {@link #push}. Server thread.
 */
public final class KillFeed {
	public static final int CAP = 8;
	/** An entry older than this is no longer shown (server ticks: 30 s). */
	public static final long SHOWN_TICKS = 600;
	private final Deque<Entry> entries = new ArrayDeque<>();

	/**
	 * One event: {@code verb} is one of {@code killed, downed, revived, bled_out, trauma, died} or another phase's word (the overlay
	 * shows unknown verbs as they are); {@code who} may be empty (nobody did it).
	 */
	public record Entry(String who, String verb, String whom, long tick) {
	}

	public void push(final String who, final String verb, final String whom, final long tick) {
		this.entries.addFirst(new Entry(who, verb, whom, tick));
		while (this.entries.size() > CAP) {
			this.entries.removeLast();
		}
	}

	/** The entries still shown at {@code tick}, newest first. */
	public List<Entry> recent(final long tick) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : this.entries) {
			if (tick - e.tick() <= SHOWN_TICKS && tick >= e.tick()) {
				out.add(e);
			}
		}

		return out;
	}

	public void clear() {
		this.entries.clear();
	}
}
