package dev.cyberblock.mode.overlord;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.DevCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dev-only Overlord spawn (with {@code -Dcyberblock.devtools=true}, ops only; tools/mpcheck.py's {@code overlord} scenario, its
 * {@code PHASE8_COMMANDS} row and shared/protocol.md "Phase 8"): {@code /cyberblock test overlord spawn <palette id> <player>}, run as
 * the Overlord of a running match, queues one {@code spawn_palette} entry (a bare id is in the cyberblock namespace) beside that
 * player, just outside the intensity's minimum distance, through {@link Overlord#request}: the same cost, cooldown, warning, fairness
 * and routing as the console. A refusal says why and changes nothing.
 */
final class OverlordTestCommand {
	private static final int TRIES = 8; // points around the player, as the Auto-Director tries them
	private static final double MARGIN = 6.0; // blocks past the minimum distance

	private OverlordTestCommand() {
	}

	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!DevCommands.enabled()) {
			return;
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("overlord").then(Commands.literal("spawn").then(Commands.argument("entry", StringArgumentType.string())
				.then(Commands.argument("player", EntityArgument.player()).executes(OverlordTestCommand::spawn)))))));
	}

	private static int spawn(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer by = c.getSource().getPlayerOrException();
		ServerPlayer near = EntityArgument.getPlayer(c, "player");
		String raw = StringArgumentType.getString(c, "entry");
		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : Cyberblock.ID + ":" + raw);
		String match = Overlord.matchOf(by);
		String why;
		if (id == null || OverlordContent.spawns().get(id) == null) {
			why = "no such spawn_palette entry";
		} else if (match == null || !Overlord.isOverlord(by)) {
			why = "not the Overlord of a running match";
		} else {
			Overlord.MatchState m = Overlord.state(match);
			double r = m.intensity.minDistance() + MARGIN;
			why = "no spot";
			for (int i = 0; i < TRIES && why != null; i++) {
				double a = i * Math.PI * 2.0 / TRIES;
				Fairness.Refusal refusal = Overlord.request(c.getSource().getServer(), m, by, false, id, near.getX() + Math.cos(a) * r,
					near.getZ() + Math.sin(a) * r);
				why = refusal == null ? null : refusal.key() + ": " + refusal.text();
			}
		}

		String line = "test: overlord spawn " + raw + " by " + by.getPlainTextName() + " near " + near.getPlainTextName() + " -> "
			+ (why == null ? "queued" : "refused (" + why + ")");
		Cyberblock.LOG.info(line);
		c.getSource().sendSuccess(() -> Component.literal(line), false);
		return why == null ? 1 : 0;
	}
}
