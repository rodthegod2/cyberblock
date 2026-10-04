package dev.cyberblock.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One backup, with no Minecraft types in it: zip the world folder into {@code <dir>/cyberblock-<date>.zip} (written as
 * {@code .zip.part}, then renamed, so a half file never looks like a backup), then keep only the newest {@code keep}. The caller has
 * already saved the world and holds saving off while this runs.
 */
final class BackupJob {
	enum Outcome { DONE, SKIPPED_DISK, CANCELLED, FAILED }

	/** {@code file} and {@code bytes} are set for DONE only; {@code detail} says why for the others. */
	record Result(Outcome outcome, String file, long bytes, String detail) {}

	private static final String PART = ".part";

	private BackupJob() {}

	static Result run(final Path world, final Path dir, final int keep, final LocalDateTime now, final LongSupplier freeBytes, final BooleanSupplier cancelled) {
		try {
			Files.createDirectories(dir);
			deleteStaleParts(dir);
			long worldBytes = size(world, dir);
			long free = freeBytes.getAsLong();
			if (!BackupRules.enoughDisk(free, worldBytes)) {
				return new Result(Outcome.SKIPPED_DISK, null, 0, "only " + BackupRules.size(free) + " free for a world of " + BackupRules.size(worldBytes)
					+ " (needs twice the world's size)");
			}

			String name = BackupRules.fileName(now, names(dir));
			Path part = dir.resolve(name + PART);
			Path target = dir.resolve(name);
			if (!zip(world, dir, part, cancelled)) {
				Files.deleteIfExists(part);
				return new Result(Outcome.CANCELLED, null, 0, "the server is stopping");
			}

			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
			for (String old : BackupRules.toPrune(names(dir), keep)) {
				if (!old.equals(name)) { // never the one just made, whatever the clock did
					Files.deleteIfExists(dir.resolve(old));
				}
			}

			return new Result(Outcome.DONE, name, Files.size(target), "");
		} catch (IOException | RuntimeException e) {
			return new Result(Outcome.FAILED, null, 0, e.toString());
		}
	}

	static List<String> names(final Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).toList();
		}
	}

	private static void deleteStaleParts(final Path dir) throws IOException {
		for (String name : names(dir)) {
			if (name.startsWith("cyberblock-") && name.endsWith(".zip" + PART)) {
				Files.deleteIfExists(dir.resolve(name));
			}
		}
	}

	/** Bytes of the world's files that the zip would hold. */
	private static long size(final Path world, final Path skipDir) throws IOException {
		long[] total = {0};
		walk(world, skipDir, (file, attrs) -> total[0] += attrs.size());
		return total[0];
	}

	/** False when cancelled (the part file is then incomplete). */
	private static boolean zip(final Path world, final Path skipDir, final Path part, final BooleanSupplier cancelled) throws IOException {
		String root = world.getFileName().toString();
		try (OutputStream out = Files.newOutputStream(part); ZipOutputStream zip = new ZipOutputStream(out)) {
			boolean[] stopped = {false};
			walk(world, skipDir, (file, attrs) -> {
				if (stopped[0] || cancelled.getAsBoolean()) {
					stopped[0] = true;
					return;
				}

				// opened before the entry is started, so a file deleted since the walk (a temp file) leaves no empty entry behind
				try (InputStream in = Files.newInputStream(file)) {
					ZipEntry entry = new ZipEntry(root + "/" + world.relativize(file).toString().replace('\\', '/'));
					entry.setTime(attrs.lastModifiedTime().toMillis());
					zip.putNextEntry(entry);
					in.transferTo(zip);
					zip.closeEntry();
				} catch (NoSuchFileException e) {
					// gone: nothing to back up
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
			return !stopped[0];
		} catch (java.io.UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private interface FileVisitor {
		void visit(Path file, BasicFileAttributes attrs);
	}

	/** Every regular file under the world, except session.lock (held open by the running server) and the backups folder itself. */
	private static void walk(final Path world, final Path skipDir, final FileVisitor visitor) throws IOException {
		Files.walkFileTree(world, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
				return dir.equals(skipDir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && !file.getFileName().toString().equals("session.lock")) {
					visitor.visit(file, attrs);
				}

				return FileVisitResult.CONTINUE;
			}
		});
	}
}
