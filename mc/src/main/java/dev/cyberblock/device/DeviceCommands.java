package dev.cyberblock.device;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.DevCommands;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /cyberblock guests devices <type> [on|off]} (a host lets guests use one device type of its Night City, or not; section 1.11's
 * toggle bits on top of {@code /cyberblock guests devices on|off}), and with {@code -Dcyberblock.devtools=true} for ops the harness
 * commands {@code /cyberblock test devact|device <x> <y> <z> <act> [row]} (a use of the device block there, as the source player: {@code use},
 * {@code sneak}, a device act or {@code buy}; with a row, {@code buy} buys it) and {@code /cyberblock test devicelist <host>} (the host's
 * devices). Brigadier merges these literals with the other {@code /cyberblock} commands.
 */
final class DeviceCommands {
	private DeviceCommands() {
	}

	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> devices = Commands.literal("devices");
		for (DeviceType type : DeviceType.values()) {
			devices = devices.then(Commands.literal(type.id())
				.executes(c -> show(c.getSource(), type))
				.then(Commands.literal("on").executes(c -> set(c.getSource(), type, true)))
				.then(Commands.literal("off").executes(c -> set(c.getSource(), type, false))));
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("guests").then(devices)));
		if (DevCommands.enabled()) {
			// test devact (the plan's name) and test device (the name 4P's harness uses) are the same command
			for (String name : new String[] {"devact", "device"}) {
				dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.literal(name).then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
						.then(Commands.argument("z", IntegerArgumentType.integer()).then(Commands.argument("act", StringArgumentType.word())
							.executes(c -> test(c.getSource(), pos(c), StringArgumentType.getString(c, "act"), -1))
							.then(Commands.argument("row", IntegerArgumentType.integer(0, Shops.MAX_ROWS - 1))
								.executes(c -> test(c.getSource(), pos(c), StringArgumentType.getString(c, "act"), IntegerArgumentType.getInteger(c, "row")))))))))));
			}

			dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("devicelist").then(Commands.argument("host", StringArgumentType.word())
					.executes(c -> list(c.getSource(), StringArgumentType.getString(c, "host")))))));
		}
	}

	private static BlockPos pos(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
		return new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"));
	}

	private static Component kindName(final DeviceType type) {
		return Component.translatableWithFallback("cyberblock.device.kind." + type.id(), type.id());
	}

	private static int show(final CommandSourceStack source, final DeviceType type) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.device.toggle.host_only", "Only the host can change what guests may use"));
			return 0;
		}

		boolean on = Permissions.of(p.getUUID()).device(type);
		Component state = Component.translatableWithFallback("cyberblock.device.toggle.state." + (on ? "on" : "off"), on ? "allowed" : "blocked");
		source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.device.toggle.line", "%s: %s", kindName(type), state), false);
		return 1;
	}

	private static int set(final CommandSourceStack source, final DeviceType type, final boolean on) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			return show(source, type);
		}

		Permissions.setDevice(p, type, on);
		Cyberblock.LOG.info("devices: {} lets guests use {}: {}", p.getPlainTextName(), type.id(), on ? "on" : "off");
		source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.device.toggle." + (on ? "on" : "off"),
			on ? "Guests can now use %s" : "Guests can no longer use %s", kindName(type)), false);
		return 1;
	}

	private static int test(final CommandSourceStack source, final BlockPos pos, final String act, final int row) {
		ServerPlayer actor = source.getPlayer();
		if (actor == null) {
			source.sendFailure(Component.literal("test: devact needs a player (execute as <player> run ...)"));
			return 0;
		}

		boolean found = DeviceModule.test(actor, pos, act.toLowerCase(Locale.ROOT), row);
		String line = String.format(Locale.ROOT, "test: devact %s at %d %d %d by %s -> %s", act, pos.getX(), pos.getY(), pos.getZ(), actor.getPlainTextName(),
			found ? "used" : "no device");
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return found ? 1 : 0;
	}

	private static int list(final CommandSourceStack source, final String host) {
		ServerPlayer p = source.getServer().getPlayerList().getPlayerByName(host);
		HostSession s = p == null ? null : HostSessions.of(p);
		if (s == null) {
			source.sendFailure(Component.literal("test: no session for " + host));
			return 0;
		}

		int n = 0;
		for (DeviceRegistry.Tracked t : DeviceModule.registry().of(s.uuid())) {
			Device d = t.device();
			String line = String.format(Locale.ROOT, "test: device %s %s %s st=%d fl=%d at %.1f %.1f %.1f cells %d", d.idText(), d.type().id(), d.cls(), d.st(), d.fl(),
				d.x(), d.y(), d.z(), t.placed().size());
			Cyberblock.LOG.info(line);
			source.sendSuccess(() -> Component.literal(line), false);
			n++;
		}

		return n;
	}
}
