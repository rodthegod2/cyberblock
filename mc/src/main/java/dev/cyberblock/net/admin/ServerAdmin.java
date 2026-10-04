package dev.cyberblock.net.admin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dedicated-server admin bits (plan task 2-M1): the startup line, the whitelist warning, `joinHelp` on refusals,
 * `/cyberblock status` and `/cyberblock hosts add|remove <name>`. Nothing here is panel-specific: the server runs on the
 * stock Fabric 26.3 server launcher jar (a panel's Custom Jar option).
 *
 * Minecraft 26.3 names (javap on the Loom jars): {@code Commands.LEVEL_GAMEMASTERS} is permission level 2 and
 * {@code Commands.LEVEL_ADMINS} level 3 (vanilla {@code WhitelistCommand} uses it); {@code MinecraftServer.isDedicatedServer()},
 * {@code isSingleplayerOwner(NameAndId)}; {@code PlayerList.isUsingWhitelist()}; {@code Player.getPlainTextName()}, {@code nameAndId()}.
 *
 * Integration (package 1N lives in {@code dev.cyberblock.net}, where its package-private config and connections are): set
 * {@link #configChanged}, {@link #versionOf}, {@link #rateOf} and {@link #contentCounts} there, wrap every handshake refusal in
 * {@link #refusal}, and call {@link #init()} after 1N's {@code NetCommands} registration.
 */
public final class ServerAdmin {
	/** `hs.proto` (plan section 1.3). */
	static final int PROTOCOL = 1;

	/** Per player: "mod X dll Y" from the handshake. Package 1N (or the integrator) sets it; empty until then. */
	public static volatile Function<UUID, String> versionOf = uuid -> "";
	/** Per player: relay rates and drops, for example "up 12/s, dropped 0". Set by 1N's relay guard; empty until then. */
	public static volatile Function<UUID, String> rateOf = uuid -> "";
	/** Loaded content per kind (plan section 1.7). Set by 1N's content loader; empty until then. */
	public static volatile Supplier<Map<String, Integer>> contentCounts = Map::of;
	/**
	 * Re-reads server.json into 1N's server config after `/cyberblock hosts` changed it. Null until wired: an edit then applies
	 * only after a server restart, and the op is told so.
	 */
	public static volatile Runnable configChanged;

	/** Used only by the warning timer's one thread. */
	private static final WhitelistWarning WARNING = new WhitelistWarning();
	private static volatile ScheduledExecutorService warnings;
	private static volatile HostsConfig.Settings settings = HostsConfig.DEFAULTS;

	private ServerAdmin() {}

	/** The one call from {@code Cyberblock.onInitialize}. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerAdmin::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopWarnings());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(commands()));
	}

	/** A refusal reason (handshake, requireMod kick) with the server's `joinHelp` appended. For package 1N's handshake. */
	public static String refusal(final String reason) {
		return AdminText.withJoinHelp(reason, settings.joinHelp());
	}

	static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cyberblock").resolve("server.json");
	}

	private static void started(final MinecraftServer server) {
		settings = HostsConfig.read(configFile());
		Cyberblock.LOG.info(AdminText.startupLine(PROTOCOL, settings.maxHosts(), settings.requireMod(), server.getPlayerList().isUsingWhitelist()));
		if (server.isDedicatedServer()) {
			// a timer, not a tick listener: an empty 26.3 dedicated server stops ticking after a minute (pause when empty),
			// and the warning matters most while nobody has joined yet. The whitelist flag is a plain field read.
			stopWarnings();
			warnings = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("cyberblock-whitelist-warning").factory());
			warnings.scheduleAtFixedRate(() -> {
				if (WARNING.due(System.nanoTime() / 1_000_000L, true, server.getPlayerList().isUsingWhitelist())) {
					Cyberblock.LOG.warn(WhitelistWarning.TEXT);
				}
			}, 0, 5, TimeUnit.SECONDS);
		}
	}

	private static void stopWarnings() {
		ScheduledExecutorService running = warnings;
		warnings = null;
		if (running != null) {
			running.shutdownNow();
		}
	}

	private static LiteralArgumentBuilder<CommandSourceStack> commands() {
		var ops = Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS);
		// editing who may host is level 3, like vanilla /whitelist: datapack functions and command blocks run at level 2
		var admins = Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_ADMINS);
		// When Brigadier merges a literal into an existing node it keeps the first node's requirement, so `hosts` carries the
		// same requirement as package 1N's `/cyberblock hosts` listing: the merged node is gated whichever registers first.
		return Commands.literal("cyberblock")
			.then(Commands.literal("status").requires(ops).executes(c -> status(c.getSource())))
			.then(Commands.literal("hosts").requires(ServerAdmin::opOrOwner)
				.then(Commands.literal("add").requires(admins)
					.then(Commands.argument("name", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerList().getPlayers().stream()
							.map(ServerPlayer::getPlainTextName).toList(), b))
						.executes(c -> hosts(c.getSource(), StringArgumentType.getString(c, "name"), true))))
				.then(Commands.literal("remove").requires(admins)
					.then(Commands.argument("name", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(HostsConfig.read(configFile()).hosts(), b))
						.executes(c -> hosts(c.getSource(), StringArgumentType.getString(c, "name"), false)))));
	}

	/** Ops (level 2) and the singleplayer/LAN owner: the same test as package 1N's {@code NetCommands.opOrOwner}. */
	private static boolean opOrOwner(final CommandSourceStack source) {
		if (Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
			return true;
		}

		ServerPlayer p = source.getPlayer();
		return p != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(p.nameAndId());
	}

	private static int status(final CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		List<AdminText.Row> rows = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			HostSession s = HostSessions.of(p);
			UUID uuid = p.getUUID();
			rows.add(new AdminText.Row(p.getPlainTextName(), s == null ? "guest" : "host", s == null ? "" : s.active() ? "active" : "linked",
				versionOf.apply(uuid), rateOf.apply(uuid)));
		}

		HostsConfig.Settings now = HostsConfig.read(configFile());
		for (String line : AdminText.status(rows, now.maxHosts(), now.requireMod(), server.getPlayerList().isUsingWhitelist(), now.hosts(),
			contentCounts.get())) {
			source.sendSuccess(() -> Component.literal(line), false);
		}

		return rows.size();
	}

	private static int hosts(final CommandSourceStack source, final String raw, final boolean add) {
		HostsConfig.Result r = HostsConfig.edit(configFile(), raw, add);
		String list = r.hosts().isEmpty() ? "-" : String.join(", ", r.hosts());
		switch (r.outcome()) {
			case ADDED, REMOVED -> {
				settings = HostsConfig.read(configFile());
				Runnable reload = configChanged;
				if (reload != null) {
					reload.run();
				}

				Cyberblock.LOG.info("admin: {} {} host {} (hosts: {})", source.getTextName(), add ? "added" : "removed", r.name(), list);
				String when = reload != null ? "at their next handshake" : "after a server restart";
				source.sendSuccess(() -> add
					? Component.translatableWithFallback("cyberblock.admin.hosts.added", "%s may now host Cyberpunk; applies %s (hosts: %s)", r.name(), when, list)
					: Component.translatableWithFallback("cyberblock.admin.hosts.removed", "%s removed from the hosts list; applies %s (hosts: %s)", r.name(), when, list),
					true);
				if (r.hosts().isEmpty()) {
					source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.admin.hosts.empty",
						"The hosts list is empty: every whitelisted player may host."), true);
				}

				return 1;
			}
			case ALREADY -> source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.admin.hosts.already",
				"%s is already on the hosts list", r.name()), false);
			case ABSENT -> source.sendFailure(Component.translatableWithFallback("cyberblock.admin.hosts.absent", "%s is not on the hosts list", r.name()));
			case BAD_NAME -> source.sendFailure(Component.translatableWithFallback("cyberblock.admin.hosts.bad_name",
				"Not a player name or UUID: %s (names are 3-16 letters, digits or _)", AdminText.cap(raw, 40)));
			case BAD_FILE -> source.sendFailure(Component.translatableWithFallback("cyberblock.admin.hosts.bad_file",
				"config/cyberblock/server.json is not valid JSON with a \"hosts\" list of names; nothing was changed. Fix it by hand."));
			case IO_ERROR -> {
				Cyberblock.LOG.warn("admin: could not write config/cyberblock/server.json");
				source.sendFailure(Component.translatableWithFallback("cyberblock.admin.hosts.io_error",
					"Could not write config/cyberblock/server.json; nothing was changed."));
			}
		}

		return 0;
	}
}
