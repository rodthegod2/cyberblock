package dev.cyberblock.mode.pvphack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.net.DevCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dev-only player hack tool (with {@code -Dcyberblock.devtools=true}, ops only; the screenshots and the in-game checks):
 * {@code /cyberblock test pvphack <hack> <target>} fires the chip from the source player at the target through the same rules and
 * counterplay as a scanned lock, without a scan, a cyberdeck, RAM or the chip item (logged as a test). The target may be the source
 * himself, so one client can see the warning, the Breach and the glitch overlay.
 */
final class PvpHackCommands {
	private PvpHackCommands() {
	}

	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!DevCommands.enabled()) {
			return;
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("pvphack").then(Commands.argument("hack", StringArgumentType.word())
				.suggests((c, b) -> {
					PvpHackRules.CHIPS.forEach(h -> b.suggest(h.wire()));
					return b.buildFuture();
				})
				.then(Commands.argument("target", EntityArgument.player()).executes(PvpHackCommands::fire))))));
	}

	private static int fire(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer hacker = c.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(c, "target");
		Hack hack = Hack.byWire(StringArgumentType.getString(c, "hack"));
		if (hack == null) {
			c.getSource().sendFailure(Component.literal("test: pvphack: no such hack"));
			return 0;
		}

		boolean went = PvpHacks.fire(hacker, target, hack, null, true);
		String line = "test: pvphack " + hack.wire() + " by " + hacker.getPlainTextName() + " on " + target.getPlainTextName() + " -> "
			+ (went ? "fired" : "refused");
		Cyberblock.LOG.info(line);
		c.getSource().sendSuccess(() -> Component.literal(line), false);
		return went ? 1 : 0;
	}
}
