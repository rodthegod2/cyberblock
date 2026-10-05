package dev.cyberblock.backup;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.ServerConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Daily server backups (megaplan 9-B1). At {@code backups.hour} (server local time) a dedicated server saves the world, holds saving off
 * (like {@code /save-off}) while a background thread zips it into {@code backups/cyberblock-<date>.zip}, restores saving, keeps the
 * newest {@code backups.keep} and logs {@code backup: <file> <size>}. {@code /cyberblock backup now} (ops) does the same on demand, also
 * on a world that is not a dedicated server; its extra zips of a day carry the time and have their own cap, so they never evict a daily one. {@code tools.server_sync pull-backups} downloads the zips.
 */
public final class Backups {
	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static final Object HOLD = new Object();
	/** Levels whose saving this class turned off, with the flag they had; only touched under HOLD. */
	private static final Map<ServerLevel, Boolean> held = new IdentityHashMap<>();
	private static volatile boolean stopping;
	private static volatile ScheduledExecutorService timer;
	/** The day a daily backup was last tried (done, skipped or failed): one try, and one warning, per day. */
	private static volatile LocalDate triedOn;
	/** The running zip thread, if any; {@link #stopped} waits for it. */
	private static volatile Thread zipThread;
	/** How long a stopping server waits for the zip to notice: it checks between 1 MB chunks, so this is ample. */
	private static final Duration STOP_WAIT = Duration.ofSeconds(10);

	private Backups() {}

	/** The one call from {@code Cyberblock.onInitialize}. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(Backups::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(Backups::stopped);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("backup").requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("now").executes(command -> now(command.getSource()))))));
	}

	private static Path backupsDir(final MinecraftServer server) {
		return server.getServerDirectory().toAbsolutePath().normalize().resolve("backups");
	}

	private static void started(final MinecraftServer server) {
		stopping = false;
		triedOn = null;
		ServerConfig.Backups config = ServerConfig.get().backups();
		if (!server.isDedicatedServer()) {
			return;
		}

		if (!config.daily()) {
			Cyberblock.LOG.info("backup: daily backups are off (backups.daily is false in config/cyberblock/server.json)");
			return;
		}

		Cyberblock.LOG.info("backup: daily at {}:00 server time, keeping {}", String.format("%02d", config.hour()), config.keep());
		// a timer, not a tick listener: an empty 26.3 dedicated server stops ticking after a minute (pause when empty)
		stopTimer();
		timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("cyberblock-backup-timer").factory());
		timer.scheduleWithFixedDelay(() -> {
			try {
				check(server);
			} catch (RuntimeException e) {
				Cyberblock.LOG.warn("backup: the daily check failed: {}", e.toString());
			}
		}, 10, 30, TimeUnit.SECONDS);
	}

	private static void check(final MinecraftServer server) {
		LocalDateTime now = LocalDateTime.now();
		if (now.toLocalDate().equals(triedOn) || RUNNING.get()) {
			return;
		}

		Path dir = backupsDir(server);
		List<String> existing;
		try {
			existing = Files.isDirectory(dir) ? BackupJob.names(dir) : List.of();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (BackupRules.due(now, ServerConfig.get().backups().hour(), existing)) {
			triedOn = now.toLocalDate();
			start(server, null);
		}
	}

	private static void stopped(final MinecraftServer server) {
		stopping = true; // a zip in progress stops and drops its half file
		stopTimer();
		// the zip thread is a daemon: without this wait the JVM can exit before it hears the flag, leaving the .part and no log line
		Thread zipping = zipThread;
		if (zipping != null) {
			try {
				if (!zipping.join(STOP_WAIT)) {
					Cyberblock.LOG.warn("backup: still zipping at shutdown; the next backup deletes its half file");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		restoreSaving(); // the shutdown save must not be skipped
	}

	private static void stopTimer() {
		ScheduledExecutorService running = timer;
		timer = null;
		if (running != null) {
			running.shutdownNow();
		}
	}

	private static int now(final CommandSourceStack source) {
		if (!start(source.getServer(), source)) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.backup.running", "A backup is already running"));
			return 0;
		}

		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.backup.started", "Backup started; the result is in the server log (backup: ...)"));
		return 1;
	}

	/** False when a backup is already running. Saves on the server thread, then zips on its own thread. {@code source} hears the result. */
	private static boolean start(final MinecraftServer server, final CommandSourceStack source) {
		if (!RUNNING.compareAndSet(false, true)) {
			return false;
		}

		server.execute(() -> {
			try {
				server.saveEverything(true, true, true);
				synchronized (HOLD) {
					for (ServerLevel level : server.getAllLevels()) {
						held.put(level, level.noSave);
						level.noSave = true;
					}
				}
			} catch (RuntimeException e) {
				finish(server, source, new BackupJob.Result(BackupJob.Outcome.FAILED, null, 0, "saving the world failed: " + e));
				return;
			}

			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path dir = backupsDir(server);
			int keep = ServerConfig.get().backups().keep();
			zipThread = Thread.ofPlatform().daemon().name("cyberblock-backup").start(() -> {
				BackupJob.Result result;
				try {
					result = BackupJob.run(world, dir, keep, LocalDateTime.now(), () -> usable(dir), () -> stopping);
				} catch (RuntimeException e) {
					result = new BackupJob.Result(BackupJob.Outcome.FAILED, null, 0, e.toString());
				}

				finish(server, source, result);
			});
		});
		return true;
	}

	private static long usable(final Path dir) {
		try {
			return Files.getFileStore(dir).getUsableSpace();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Logs the result, turns saving back on (on the server thread) and tells whoever asked. Callable from any thread. */
	private static void finish(final MinecraftServer server, final CommandSourceStack source, final BackupJob.Result result) {
		switch (result.outcome()) {
			case DONE -> Cyberblock.LOG.info("backup: {} {}", result.file(), BackupRules.size(result.bytes()));
			case SKIPPED_DISK -> Cyberblock.LOG.warn("backup: skipped, {}", result.detail());
			case CANCELLED -> Cyberblock.LOG.info("backup: stopped, {}", result.detail());
			case FAILED -> Cyberblock.LOG.warn("backup: failed, {}", result.detail());
		}

		Runnable after = () -> {
			restoreSaving();
			RUNNING.set(false);
			if (source != null) {
				source.sendSystemMessage(Component.literal(result.outcome() == BackupJob.Outcome.DONE
					? "backup: " + result.file() + " " + BackupRules.size(result.bytes())
					: "backup: not made (" + result.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "), see the server log"));
			}
		};
		if (stopping) {
			after.run(); // the server loop is gone: nothing would run a queued task
		} else {
			server.execute(after);
		}
	}

	private static void restoreSaving() {
		synchronized (HOLD) {
			for (Map.Entry<ServerLevel, Boolean> e : new ArrayList<>(held.entrySet())) {
				e.getKey().noSave = e.getValue();
			}

			held.clear();
		}
	}
}
