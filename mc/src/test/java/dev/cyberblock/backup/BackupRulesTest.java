package dev.cyberblock.backup;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackupRulesTest {
	private static final LocalDateTime FIVE_OH_ONE = LocalDateTime.of(2026, 10, 4, 5, 1, 7);

	@Test
	void theFileNameIsTheDateAndTheTimeOnlyWhenTheDayAlreadyHasOne() {
		assertEquals("cyberblock-2026-10-04.zip", BackupRules.fileName(FIVE_OH_ONE, List.of()));
		assertEquals("cyberblock-2026-10-04-050107.zip", BackupRules.fileName(FIVE_OH_ONE, List.of("cyberblock-2026-10-04.zip")));
		assertEquals("cyberblock-2026-10-04.zip", BackupRules.fileName(FIVE_OH_ONE, List.of("cyberblock-2026-10-03.zip")));
	}

	@Test
	void aBackupIsDueFromTheConfiguredHourUntilTodaysExists() {
		assertFalse(BackupRules.due(LocalDateTime.of(2026, 10, 4, 4, 59, 59), 5, List.of()), "before the hour");
		assertTrue(BackupRules.due(FIVE_OH_ONE, 5, List.of()), "at the hour");
		assertTrue(BackupRules.due(LocalDateTime.of(2026, 10, 4, 23, 0), 5, List.of("cyberblock-2026-10-03.zip")), "a server that was down at the hour catches up");
		assertFalse(BackupRules.due(FIVE_OH_ONE, 5, List.of("cyberblock-2026-10-04.zip")), "done today");
		assertFalse(BackupRules.due(FIVE_OH_ONE, 5, List.of("cyberblock-2026-10-04-020000.zip")), "a manual one today counts");
		assertTrue(BackupRules.due(FIVE_OH_ONE, 5, List.of("cyberblock-2026-10-04.zip.part", "notes.txt")), "a half file and strangers do not");
		assertTrue(BackupRules.due(LocalDateTime.of(2026, 10, 4, 0, 0), 0, List.of()), "hour 0 is midnight");
	}

	@Test
	void pruningKeepsTheNewestAndNeverTouchesOtherFiles() {
		List<String> names = List.of("cyberblock-2026-10-02.zip", "cyberblock-2026-10-04-050107.zip", "cyberblock-2026-10-04.zip",
			"cyberblock-2026-10-01.zip", "cyberblock-2026-10-03.zip", "world.zip", "cyberblock-2026-10-05.zip.part");
		assertEquals(List.of("cyberblock-2026-10-01.zip", "cyberblock-2026-10-02.zip", "cyberblock-2026-10-03.zip"), BackupRules.toPrune(names, 2));
		assertEquals(List.of(), BackupRules.toPrune(names, 5));
		assertEquals(List.of(), BackupRules.toPrune(names, 99));
		assertEquals(5, BackupRules.toPrune(names, 0).size());
	}

	@Test
	void theDiskCheckNeedsTwiceTheWorldSize() {
		assertTrue(BackupRules.enoughDisk(200, 100));
		assertFalse(BackupRules.enoughDisk(199, 100));
		assertTrue(BackupRules.enoughDisk(0, 0));
	}

	@Test
	void sizesAreHumanReadable() {
		assertEquals("0 B", BackupRules.size(0));
		assertEquals("1023 B", BackupRules.size(1023));
		assertEquals("1.0 KB", BackupRules.size(1024));
		assertEquals("12.5 MB", BackupRules.size(13_107_200));
		assertEquals("2.0 GB", BackupRules.size(2L << 30));
	}
}
