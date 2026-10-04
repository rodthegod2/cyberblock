package dev.cyberblock.mode.match;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.cyberblock.net.DevCommands;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Matches, PvP and Gang Wars (megaplan 8-M1), their own entrypoint: the PvP toggle and its damage rule ({@link Pvp}), the
 * {@code match_mode} content kind, the Match Terminal's screen data and requests and the match tick ({@link Matches}), name tags
 * ({@link NameTags}) and the commands {@code /cyberblock pvp [on|off]}, {@code /cyberblock match} (the list),
 * {@code /cyberblock match join <id> [teammate|hacker|spectator|overlord]} (an invite's [Join] link) and
 * {@code /cyberblock match leave}. Matches are created at a Match Terminal in the Afterlife, only by Cyberpunk-linked players.
 */
public final class MatchMod implements ModInitializer {
	@Override
	public void onInitialize() {
		Pvp.init();
		Matches.init();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			dispatcher.register(command());
			if (DevCommands.enabled()) {
				dispatcher.register(devCommand());
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(Matches::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Matches.stopping();
			Pvp.stopping(server);
		});
		ServerTickEvents.END_SERVER_TICK.register(Matches::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Matches.disconnected(handler.getPlayer());
			Pvp.disconnected(handler.getPlayer());
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer victim) {
				Matches.died(victim, source.getEntity() instanceof ServerPlayer killer ? killer : null);
			}
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("cyberblock").then(Commands.literal("match")
			.executes(c -> list(c.getSource()))
			.then(Commands.literal("leave").executes(c -> Matches.commandLeave(c.getSource().getPlayerOrException())))
			.then(Commands.literal("join").then(Commands.argument("id", StringArgumentType.word())
				.executes(c -> Matches.commandJoin(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id"), "teammate"))
				.then(Commands.argument("role", StringArgumentType.word())
					.suggests((c, b) -> {
						for (Match.Role r : Match.Role.values()) {
							b.suggest(r.key());
						}

						return b.buildFuture();
					})
					.executes(c -> Matches.commandJoin(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id"),
						StringArgumentType.getString(c, "role")))))));
	}

	/**
	 * Dev only ({@code -Dcyberblock.devtools=true}, ops; for tools/mpcheck.py over RCON with {@code execute as <player> run ...}):
	 * {@code /cyberblock test terminal} is a right-click on a Match Terminal where the player stands (the screen opens, and creating
	 * is allowed from there); {@code /cyberblock test match <json>} is one {@code act match} from that player, through the same code.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> devCommand() {
		return Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("terminal").executes(c -> Matches.devTerminal(c.getSource().getPlayerOrException())))
			.then(Commands.literal("match").then(Commands.argument("json", StringArgumentType.greedyString())
				.executes(c -> Matches.devAct(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "json"))))));
	}

	private static int list(final CommandSourceStack source) {
		List<Component> lines = Matches.status();
		// system messages: the world's send_command_feedback is off (CyberblockClient), which would hide sendSuccess
		source.sendSystemMessage(lines.isEmpty()
			? Component.translatableWithFallback("cyberblock.match.none", "No matches. A linked player creates one at a Match Terminal in the Afterlife")
			: Component.translatableWithFallback("cyberblock.match.list", "Matches (%s):", lines.size()));
		lines.forEach(source::sendSystemMessage);
		return lines.size();
	}
}
