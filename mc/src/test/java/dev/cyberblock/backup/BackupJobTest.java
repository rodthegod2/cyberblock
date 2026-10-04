package dev.cyberblock.backup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupJobTest {
	private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 4, 5, 0, 0);
	private static final long PLENTY = Long.MAX_VALUE / 4;

	@TempDir Path root;
	private Path world;
	private Path backups;

	@BeforeEach
	void makeWorld() throws IOException {
		this.world = this.root.resolve("cyberblock").toAbsolutePath().normalize();
		this.backups = this.root.resolve("backups").toAbsolutePath().normalize();
		write("level.dat", "level");
		write("dimensions/minecraft/overworld/region/r.0.0.mca", "region bytes");
		write("session.lock", "held open by the server");
	}

	private void write(final String rel, final String text) throws IOException {
		Path p = this.world.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, text, StandardCharsets.UTF_8);
	}

	private BackupJob.Result run(final int keep, final LocalDateTime when, final long free) {
		return BackupJob.run(this.world, this.backups, keep, when, () -> free, () -> false);
	}

	private static Map<String, String> read(final Path zip) throws IOException {
		Map<String, String> out = new HashMap<>();
		try (ZipFile z = new ZipFile(zip.toFile())) {
			for (ZipEntry e : java.util.Collections.list(z.entries())) {
				out.put(e.getName(), new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
			}
		}

		return out;
	}

	@Test
	void theZipHoldsTheWorldUnderItsFolderNameWithoutTheSessionLock() throws IOException {
		BackupJob.Result r = run(7, DAY, PLENTY);
		assertEquals(BackupJob.Outcome.DONE, r.outcome(), r.detail());
		assertEquals("cyberblock-2026-10-04.zip", r.file());
		assertEquals(Files.size(this.backups.resolve(r.file())), r.bytes());
		assertEquals(Map.of("cyberblock/level.dat", "level", "cyberblock/dimensions/minecraft/overworld/region/r.0.0.mca", "region bytes"),
			read(this.backups.resolve(r.file())));
		assertEquals(List.of("cyberblock-2026-10-04.zip"), BackupJob.names(this.backups), "no .part file is left");
	}

	@Test
	void aBackupsFolderInsideTheWorldIsNotZippedIntoItself() throws IOException {
		this.backups = this.world.resolve("backups");
		assertEquals(BackupJob.Outcome.DONE, run(7, DAY, PLENTY).outcome());
		BackupJob.Result second = run(7, DAY.plusDays(1), PLENTY);
		assertFalse(read(this.backups.resolve(second.file())).keySet().stream().anyMatch(n -> n.contains("backups/")));
	}

	@Test
	void onlyTheNewestKeepAreLeftAndOtherFilesStay() throws IOException {
		Files.createDirectories(this.backups);
		for (int day = 1; day <= 4; day++) {
			Files.writeString(this.backups.resolve("cyberblock-2026-10-0" + day + ".zip"), "old");
		}

		Files.writeString(this.backups.resolve("keep-me.txt"), "mine");
		BackupJob.Result r = run(3, DAY.plusDays(1), PLENTY); // the 5th
		assertEquals(BackupJob.Outcome.DONE, r.outcome());
		assertEquals(List.of("cyberblock-2026-10-03.zip", "cyberblock-2026-10-04.zip", "cyberblock-2026-10-05.zip", "keep-me.txt"),
			BackupJob.names(this.backups).stream().sorted().toList());
	}

	@Test
	void aSecondBackupTheSameDayKeepsTheFirst() throws IOException {
		assertEquals("cyberblock-2026-10-04.zip", run(7, DAY, PLENTY).file());
		BackupJob.Result again = run(7, DAY.plusHours(9).plusMinutes(30).plusSeconds(5), PLENTY);
		assertEquals("cyberblock-2026-10-04-143005.zip", again.file());
		assertEquals(2, BackupJob.names(this.backups).size());
	}

	@Test
	void manyBackupsOneDayWithKeepTwoAlwaysLeaveTheNewestTwo() throws IOException {
		// found on a real server: after the plain name was pruned, the next backup reused it and pruned itself
		LocalDateTime t = DAY;
		for (int i = 0; i < 5; i++) {
			BackupJob.Result r = run(2, t, PLENTY);
			assertEquals(BackupJob.Outcome.DONE, r.outcome(), r.detail());
			assertTrue(Files.exists(this.backups.resolve(r.file())), "the new backup is never the one pruned");
			t = t.plusSeconds(7);
		}

		assertEquals(List.of("cyberblock-2026-10-04-050021.zip", "cyberblock-2026-10-04-050028.zip"),
			BackupJob.names(this.backups).stream().sorted().toList());
	}

	@Test
	void lowDiskSkipsWithAWarningAndWritesNothing() throws IOException {
		long worldBytes = "level".length() + "region bytes".length();
		BackupJob.Result r = run(7, DAY, 2 * worldBytes - 1);
		assertEquals(BackupJob.Outcome.SKIPPED_DISK, r.outcome());
		assertTrue(r.detail().contains("twice"), r.detail());
		assertEquals(List.of(), BackupJob.names(this.backups));
		assertEquals(BackupJob.Outcome.DONE, run(7, DAY, 2 * worldBytes).outcome(), "exactly twice is enough");
	}

	@Test
	void aCancelledBackupLeavesNoFileAndKeepsTheOlderOnes() throws IOException {
		Files.createDirectories(this.backups);
		Files.writeString(this.backups.resolve("cyberblock-2026-10-03.zip"), "old");
		BackupJob.Result r = BackupJob.run(this.world, this.backups, 1, DAY, () -> PLENTY, () -> true);
		assertEquals(BackupJob.Outcome.CANCELLED, r.outcome());
		assertEquals(List.of("cyberblock-2026-10-03.zip"), BackupJob.names(this.backups));
	}

	@Test
	void aStaleHalfFileFromACrashIsClearedFirst() throws IOException {
		Files.createDirectories(this.backups);
		Files.writeString(this.backups.resolve("cyberblock-2026-10-03.zip.part"), "half");
		assertEquals(BackupJob.Outcome.DONE, run(7, DAY, PLENTY).outcome());
		assertEquals(List.of("cyberblock-2026-10-04.zip"), BackupJob.names(this.backups));
	}

	@Test
	void aMissingWorldFailsInsteadOfWritingAnEmptyBackup() {
		BackupJob.Result r = BackupJob.run(this.root.resolve("nowhere"), this.backups, 7, DAY, () -> PLENTY, () -> false);
		assertEquals(BackupJob.Outcome.FAILED, r.outcome());
		assertFalse(Files.exists(this.backups.resolve("cyberblock-2026-10-04.zip")));
	}
}
