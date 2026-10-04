package dev.cyberblock.settings;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.OutlineRules;
import dev.cyberblock.holo.HoloModule;
import dev.cyberblock.mode.match.Pvp;
import dev.cyberblock.mode.overlord.Intensity;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerConfig;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.skin.CyberSkins;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server half of the settings screen (megaplan 9-S1): {@code /cyberblock difficulty [overlord <chill|normal|brutal> | heat <25-200>]}
 * (showing it: everyone; changing it: operators and the singleplayer owner), and the screen's snapshot: a {@code cyberblock:act}
 * {@code settings} is answered with {@code cyberblock:ui} {@code settings} ({@link SettingsWire}) at the end of the tick, so a command the
 * screen sent just before it is already applied. A player still locked by the join password gets nothing (ServerNet drops its acts).
 */
public final class ServerSettings {
	/** Players who asked for a snapshot this tick (answered once each at the end of the tick, however often they asked). */
	private static final Set<UUID> asked = new LinkedHashSet<>();

	private ServerSettings() {
	}

	/** Common init, once (Cyberblock.onInitialize). */
	public static void init() {
		ServerNet.onAct(SettingsWire.T, (player, m) -> asked.add(player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(ServerSettings::answer);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			Difficulty.attach(s);
			Cyberblock.LOG.info("settings: difficulty: {} (world setting cyberblock:difficulty; /cyberblock difficulty)", Difficulty.describe());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			asked.clear();
			Difficulty.use(new Difficulty.Data());
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
	}

	private static void answer(final MinecraftServer server) {
		if (asked.isEmpty()) {
			return;
		}

		for (UUID uuid : asked) {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if (p != null) {
				ServerNet.ui(p, SettingsWire.T, SettingsWire.json(snapshot(p)));
			}
		}

		asked.clear();
	}

	/** Operators (level 2) and the singleplayer/LAN owner: who may change the server's settings. */
	static boolean admin(final CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || p != null && !source.getServer().isDedicatedServer()
			&& source.getServer().isSingleplayerOwner(p.nameAndId());
	}

	/** What the screen shows this player; server.json's rows only to an admin, the join password only as on or off. */
	static SettingsWire.Snapshot snapshot(final ServerPlayer p) {
		boolean admin = admin(p.createCommandSourceStack());
		Permissions perms = Permissions.of(p.getUUID());
		return new SettingsWire.Snapshot(perms.bits(), perms.devices(), CyberSkins.body(p), Pvp.on(p),
			Boolean.TRUE.equals(p.getAttachedOrElse(HoloModule.HIDE_PIN, false)), HostSessions.of(p) != null, admin, OutlineRules.creativeErases(),
			OutlineRules.creativeErasesSource() == OutlineRules.Source.SERVER_JSON, Difficulty.overlordDefault(), Difficulty.heatPercent(),
			admin ? world(ServerConfig.get()) : null);
	}

	static SettingsWire.World world(final ServerConfig c) {
		return new SettingsWire.World(c.allowHosts(), c.hosts().size(), c.maxHosts(), c.requireMod(), c.rate(), c.sanityRadius(), c.pvpDefault(),
			c.backups().daily(), c.backups().keep(), c.backups().hour(), c.webMap(), c.joinPasswordOn(), !c.joinHelp().isBlank());
	}

	private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		var overlord = Commands.literal("overlord").requires(ServerSettings::admin);
		for (Intensity i : Intensity.values()) {
			overlord = overlord.then(Commands.literal(i.key()).executes(c -> setOverlord(c.getSource(), i)));
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("difficulty")
			.executes(c -> show(c.getSource()))
			.then(overlord)
			.then(Commands.literal("heat").requires(ServerSettings::admin)
				.then(Commands.argument("percent", IntegerArgumentType.integer(Difficulty.HEAT_MIN, Difficulty.HEAT_MAX))
					.executes(c -> setHeat(c.getSource(), IntegerArgumentType.getInteger(c, "percent")))))));
	}

	// system messages: the world's send_command_feedback is off (CyberblockClient), which would hide sendSuccess
	private static int show(final CommandSourceStack source) {
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.difficulty.status",
			"Overlord intensity for new matches: %s. Heat scaling: %s %%", Difficulty.overlordDefault().key(), Difficulty.heatPercent()));
		return 1;
	}

	private static int setOverlord(final CommandSourceStack source, final Intensity value) {
		if (Difficulty.setOverlord(value)) {
			Cyberblock.LOG.info("settings: Overlord intensity default {} (by {})", value.key(), source.getTextName());
		}

		return show(source);
	}

	private static int setHeat(final CommandSourceStack source, final int percent) {
		if (Difficulty.setHeat(percent)) {
			Cyberblock.LOG.info("settings: Heat scaling {} % (by {})", percent, source.getTextName());
		}

		return show(source);
	}
}
