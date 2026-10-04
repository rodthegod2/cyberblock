package dev.cyberblock.net;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /cyberblock guests [<toggle> on|off]} (a host decides what guests may do to its own Cyberpunk game, section 1.6) and
 * {@code /cyberblock hosts} (ops and the singleplayer owner: every player's role, versions, session state and message rates).
 * Brigadier merges these literals with the other {@code /cyberblock} commands.
 */
public final class NetCommands {
	private NetCommands() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> guests = Commands.literal("guests").executes(c -> showGuests(c.getSource()));
		for (Permissions.Toggle t : Permissions.Toggle.values()) {
			guests = guests.then(Commands.literal(t.id())
				.executes(c -> showGuests(c.getSource()))
				.then(Commands.literal("on").executes(c -> setGuests(c.getSource(), t, true)))
				.then(Commands.literal("off").executes(c -> setGuests(c.getSource(), t, false))));
		}

		dispatcher.register(Commands.literal("cyberblock")
			.then(guests)
			.then(Commands.literal("hosts").requires(NetCommands::opOrOwner).executes(c -> hosts(c.getSource()))));
	}

	/** Ops (permission level 2) and the singleplayer/LAN owner. */
	static boolean opOrOwner(final CommandSourceStack source) {
		if (Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
			return true;
		}

		ServerPlayer p = source.getPlayer();
		return p != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(p.nameAndId());
	}

	/** "explosions on, damage on, hacks on, devices on, spawns off". */
	static String describe(final Permissions perms) {
		List<String> parts = new ArrayList<>();
		for (Permissions.Toggle t : Permissions.Toggle.values()) {
			parts.add(t.id() + (perms.has(t) ? " on" : " off"));
		}

		return String.join(", ", parts);
	}

	private static int showGuests(final CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.guests.player_only",
				"Only a player can set what guests may do to their own Cyberpunk game"));
			return 0;
		}

		String now = describe(Permissions.of(p.getUUID()));
		source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.guests.status", "What guests may do to your Cyberpunk game: %s", now), false);
		return 1;
	}

	private static int setGuests(final CommandSourceStack source, final Permissions.Toggle toggle, final boolean on) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			return showGuests(source);
		}

		ServerNet.setPerms(p, Permissions.of(p.getUUID()).with(toggle, on));
		String state = on ? "on" : "off";
		source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.guests.set", "Guest %s: %s", toggle.id(), state), false);
		return 1;
	}

	private static int hosts(final CommandSourceStack source) {
		List<ServerNet.Conn> conns = ServerNet.conns();
		if (conns.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No players"), false);
			return 0;
		}

		for (ServerNet.Conn c : conns) {
			HostSession s = HostSessions.of(c.uuid);
			String line = s == null
				? String.format(Locale.ROOT, "%s: %s, mod %s, dll %s", c.name, c.handshaken ? c.result.role() : "no handshake", or(c.mod), or(c.dll))
				: String.format(Locale.ROOT, "%s: host %s, mod %s, dll %s, up %.1f/s, down %.1f/s, queued %d, dropped %d", c.name,
					s.state().name().toLowerCase(Locale.ROOT), or(c.mod), or(c.dll), s.upRate(), s.downRate(), s.queued(), s.droppedDown());
			source.sendSuccess(() -> Component.literal(line), false);
		}

		return conns.size();
	}

	private static String or(final String version) {
		return version.isEmpty() ? "none" : version;
	}
}
