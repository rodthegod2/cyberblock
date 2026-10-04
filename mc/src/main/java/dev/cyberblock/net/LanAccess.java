package dev.cyberblock.net;

import com.mojang.authlib.GameProfile;
import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Offline guests on a world opened to LAN (Re-test 1 finding 5). Vanilla 26.3 authenticates every remote login to a LAN world
 * (IntegratedServer.initServer calls setUsesAuthentication(true); publishServer leaves it alone; javap), so a client without a
 * Minecraft account is refused with "Failed to log in: Invalid session". {@code /cyberblock lan offline on} (the world's owner only;
 * off at every world start) turns authentication off for new logins, {@code ... off} turns it back on. While it is off:
 * <ul>
 * <li>the join password (JoinLock) applies to offline names exactly as to anyone else;</li>
 * <li>nobody else may use the owner's name: IntegratedServer.isSingleplayerOwner compares names only (javap), so a remote login with
 * the owner's name would be treated as the owner (operator rights, no /login); it is refused while the owner plays.</li>
 * </ul>
 * Dedicated servers are never touched (their server.properties online-mode decides).
 */
public final class LanAccess {
	static final String IMPOSTOR = "Cyberblock: that name belongs to this world's owner. Join with another name.";
	private static volatile boolean offline;

	private LanAccess() {
	}

	/** Common init (Cyberblock.onInitialize). */
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("lan").requires(LanAccess::mayChange)
				.executes(c -> command(c.getSource(), null))
				.then(Commands.literal("offline")
					.then(Commands.literal("on").executes(c -> command(c.getSource(), true)))
					.then(Commands.literal("off").executes(c -> command(c.getSource(), false)))))));
		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			GameProfile owner = server.isDedicatedServer() ? null : singleplayerProfile(server);
			GameProfile joining = handler.getOwner();
			if (owner != null && impostor(server.usesAuthentication(), owner.name(), joining.name())) {
				Cyberblock.LOG.warn("lan: refused a login as {} (the owner's name) while offline logins are on", joining.name());
				handler.disconnect(Component.translatableWithFallback("cyberblock.lan.impostor", IMPOSTOR));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> offline = false);
	}

	/** The integrated server's owner profile (MinecraftServer has no getter on the common side; the owner's player is the first one). */
	private static @Nullable GameProfile singleplayerProfile(final MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (server.isSingleplayerOwner(p.nameAndId())) {
				return p.getGameProfile();
			}
		}

		return null;
	}

	/**
	 * Pure: a login to refuse because it would pass as the owner, who is already playing (the owner's own login comes first, before
	 * the world can be opened to LAN). Only without authentication (with it, Mojang vouches for the name); the name equals the
	 * owner's in any case, as isSingleplayerOwner compares.
	 */
	static boolean impostor(final boolean authenticated, final String ownerName, final String name) {
		return !authenticated && name.equalsIgnoreCase(ownerName);
	}

	/**
	 * The world's owner (also from the server console of its own integrated server); never on a dedicated server. Vanilla also
	 * asks this with a stand-in source that has no server (ClientboundCommandsPacket, while it builds the command tree): no.
	 */
	static boolean mayChange(final CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		if (server == null || server.isDedicatedServer()) {
			return false;
		}

		ServerPlayer player = source.getPlayer();
		return player == null ? Commands.LEVEL_OWNERS.check(source.permissions()) : server.isSingleplayerOwner(player.nameAndId());
	}

	/** {@code /cyberblock lan} (on null: the status) and {@code /cyberblock lan offline on|off}. */
	private static int command(final CommandSourceStack source, final @Nullable Boolean on) {
		MinecraftServer server = source.getServer();
		if (on != null && on != offline) {
			offline = on;
			server.setUsesAuthentication(!on);
			Cyberblock.LOG.info("lan: offline logins {} (by {})", on ? "ON: players without a Minecraft account may join under any name" : "off", source.getTextName());
		}

		boolean password = ServerConfig.get().joinPasswordOn();
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.lan.status", "LAN: %s; logins: %s; join password: %s",
			server.isPublished() ? "open on port " + server.getPort() : "not open (Esc > Open to LAN)",
			server.usesAuthentication() ? "Minecraft accounts only" : "offline names allowed", password ? "on" : "off"));
		if (!server.usesAuthentication() && !password) {
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.lan.no_password",
				"Anyone on your network can join under any name: set joinPassword in config/cyberblock/server.json and reopen the world to ask for a password"));
		}

		return 1;
	}
}
