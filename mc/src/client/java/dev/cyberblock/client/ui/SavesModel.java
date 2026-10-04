package dev.cyberblock.client.ui;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Night City's save list (megaplan 9-U1, Saves.reds header): the {@code reply} to {@code uiact journalreq arg:"saves"} read
 * as saves. Group 0 is the status (hash 0, title "ok" or "unavailable: why", no lines); every further group is a save: hash =
 * its index in Cyberpunk's list, title = its location, done = Cyberpunk marks it invalid; line 1 its name (the load key), line
 * 2 "type|level|seconds played|timestamp|life path", line 3 the tracked job.
 */
public final class SavesModel {
	/** The {@code uiact.arg} limit in UTF-8 bytes (protocol.md section 1.1): a longer save name cannot be sent back. */
	public static final int ARG_BYTES = 64;
	/** Any timestamp outside 2000..2200 is not read as a date. */
	private static final long EARLIEST = 946_684_800L;
	private static final long LATEST = 7_258_118_400L;
	/** Seconds between 1601-01-01 (Windows FILETIME) and 1970-01-01. */
	private static final long FILETIME_EPOCH = 11_644_473_600L;

	private SavesModel() {
	}

	/**
	 * One save.
	 *
	 * @param index its index in Cyberpunk's list (the {@code uiact.id} of a load)
	 * @param key its name (the {@code uiact.arg} of a load)
	 * @param type Cyberpunk's save type (ManualSave, QuickSave, AutoSave, PointOfNoReturn, EndGameSave), "" without metadata
	 * @param level V's level, -1 unknown
	 * @param played seconds played, -1 unknown
	 * @param time when it was saved, null unknown
	 */
	public record Save(int index, String key, String title, String type, int level, long played, @Nullable Instant time, String job, String lifePath,
		boolean damaged) {
		/** It can be loaded from Minecraft: Cyberpunk does not mark it invalid and its name fits {@code uiact.arg}. */
		public boolean loadable() {
			return !this.damaged && !this.key.isEmpty() && fitsArg(this.key);
		}
	}

	/**
	 * The list as Cyberpunk sent it, newest first where the times are known.
	 *
	 * @param why "" when {@code available}, else why Night City gave no list
	 */
	public record Saves(boolean available, String why, List<Save> list) {
	}

	/** The reply as a save list; an unavailable list (with the reason) when it is not one. */
	public static Saves of(final UiModel.Reply reply) {
		List<UiModel.Group> groups = reply.groups();
		if (groups.isEmpty() || groups.getFirst().hash() != 0L || !groups.getFirst().lines().isEmpty()) {
			return new Saves(false, "not a save list", List.of());
		}

		String status = groups.getFirst().title();
		if (!status.equals("ok")) {
			String why = status.startsWith("unavailable: ") ? status.substring("unavailable: ".length()) : status;
			return new Saves(false, why.isEmpty() ? "unavailable" : why, List.of());
		}

		List<Save> list = new ArrayList<>();
		for (UiModel.Group group : groups.subList(1, groups.size())) {
			if (group.hash() > Integer.MAX_VALUE) {
				continue;
			}

			String key = "", meta = "", job = "";
			for (UiModel.Line line : group.lines()) {
				switch ((int) line.hash()) {
					case 1 -> key = line.text();
					case 2 -> meta = line.text();
					case 3 -> job = line.text();
					default -> {
					}
				}
			}

			String[] f = meta.split("\\|", -1);
			list.add(new Save((int) group.hash(), key, group.title().isEmpty() ? key : group.title(), field(f, 0), (int) number(field(f, 1), Integer.MAX_VALUE),
				number(field(f, 2), Long.MAX_VALUE), time(field(f, 3)), job, field(f, 4), group.done()));
		}

		// newest first; saves without a time keep Cyberpunk's order after the dated ones
		list.sort(Comparator.comparing(Save::time, Comparator.nullsLast(Comparator.reverseOrder())));
		return new Saves(true, "", List.copyOf(list));
	}

	private static String field(final String[] fields, final int i) {
		return i < fields.length ? fields[i] : "";
	}

	/** A whole number 0..max, -1 when it is none. */
	private static long number(final String text, final long max) {
		try {
			long v = Long.parseLong(text.trim());
			return v >= 0 && v <= max ? v : -1L;
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	/**
	 * Cyberpunk's Uint64 save timestamp, whose unit the 2.31 scripts do not state: read as Unix seconds, Unix milliseconds or
	 * Windows FILETIME (100 ns since 1601), whichever lands in 2000..2200; null when none does.
	 */
	public static @Nullable Instant time(final String text) {
		long raw;
		try {
			raw = Long.parseUnsignedLong(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}

		if (raw < 0L) {
			return null; // above Long.MAX_VALUE: no unit above makes that a date
		}

		for (long seconds : new long[] {raw, raw / 1000L, raw / 10_000_000L - FILETIME_EPOCH}) {
			if (seconds >= EARLIEST && seconds <= LATEST) {
				return Instant.ofEpochSecond(seconds);
			}
		}

		return null;
	}

	/** "12h 05m" (or "5m"), "" when unknown. */
	public static String played(final long seconds) {
		if (seconds < 0L) {
			return "";
		}

		long minutes = seconds / 60L;
		return minutes >= 60L ? String.format("%dh %02dm", minutes / 60L, minutes % 60L) : minutes + "m";
	}

	/** A save name fits {@code uiact.arg} (at most 64 UTF-8 bytes, no control characters the wire would strip). */
	public static boolean fitsArg(final String key) {
		return key.getBytes(StandardCharsets.UTF_8).length <= ARG_BYTES && UiModel.clean(key, ARG_BYTES).equals(key);
	}
}
