package dev.cyberblock.backup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The pure decisions of the daily backups (megaplan 9-B1): which file name, when one is due, what to prune, whether the disk allows it. */
final class BackupRules {
	/** {@code cyberblock-2026-10-04.zip}; a second backup the same day (a manual one) adds the time: {@code cyberblock-2026-10-04-143005.zip}. */
	private static final Pattern NAME = Pattern.compile("cyberblock-(\\d{4}-\\d{2}-\\d{2})(-\\d{6})?\\.zip");
	/**
	 * Timed zips (the extra manual ones of a day) have their own small budget, so {@code /cyberblock backup now} run again and again never
	 * evicts a daily zip: the older days are what a restore after griefing needs.
	 */
	static final int MANUAL_KEEP = 3;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

	private BackupRules() {}

	static boolean isBackup(final String name) {
		return NAME.matcher(name).matches();
	}

	/**
	 * The file name for a backup taken at {@code now}: the plain daily name for the day's first backup, with the time for every later
	 * one. The plain name sorts before the timed ones, so it must never be reused once a timed one exists (pruning goes by name order).
	 */
	static String fileName(final LocalDateTime now, final Collection<String> existing) {
		return hasBackupOn(now.toLocalDate(), existing)
			? "cyberblock-" + DAY.format(now) + "-" + TIME.format(now) + ".zip"
			: "cyberblock-" + DAY.format(now) + ".zip";
	}

	/** The configured hour has come (server local time) and no backup of today exists yet. */
	static boolean due(final LocalDateTime now, final int hour, final Collection<String> existing) {
		return now.getHour() >= hour && !hasBackupOn(now.toLocalDate(), existing);
	}

	static boolean hasBackupOn(final LocalDate day, final Collection<String> existing) {
		String prefix = "cyberblock-" + DAY.format(day);
		return existing.stream().anyMatch(name -> isBackup(name) && name.startsWith(prefix));
	}

	/**
	 * The backups to delete, oldest first: the daily (plain-name) zips beyond the newest {@code keep}, and the timed zips beyond the newest
	 * {@link #MANUAL_KEEP}. The two budgets are apart, so timed zips never push out a daily one. A day's first backup, manual or not, has
	 * the plain name and is that day's daily zip.
	 */
	static List<String> toPrune(final Collection<String> existing, final int keep) {
		return Stream.concat(beyondNewest(existing, false, keep), beyondNewest(existing, true, MANUAL_KEEP))
			.sorted(Comparator.comparing(BackupRules::stem)).toList();
	}

	/** Names sort chronologically within one kind (all plain names, or all timed names, have the same shape). */
	private static Stream<String> beyondNewest(final Collection<String> existing, final boolean timed, final int keep) {
		List<String> sorted = existing.stream().filter(name -> isBackup(name) && isTimed(name) == timed).sorted().toList();
		return sorted.subList(0, Math.max(0, sorted.size() - keep)).stream();
	}

	private static boolean isTimed(final String name) {
		Matcher m = NAME.matcher(name);
		return m.matches() && m.group(2) != null;
	}

	private static String stem(final String name) {
		return name.substring(0, name.length() - ".zip".length());
	}

	/** The disk has room for the zip: at least twice the world's size is free (the zip is about the world's size at worst, plus slack). */
	static boolean enoughDisk(final long freeBytes, final long worldBytes) {
		return freeBytes >= 2 * worldBytes;
	}

	static String size(final long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}

		String[] units = {"KB", "MB", "GB", "TB"};
		double value = bytes;
		int unit = -1;
		do {
			value /= 1024;
			unit++;
		} while (value >= 1024 && unit < units.length - 1);

		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}
}
