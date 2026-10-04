// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import dev.cyberblock.content.ContentLoader;
import dev.cyberblock.net.DevCommands;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.NetCommands;
import dev.cyberblock.net.ServerNet;
import java.util.function.Predicate;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The common entrypoint. Megaplan section 1.4: no JVM-shared host state lives here any more (the old active, events and launching
 * statics are gone): the server keeps one HostSession per host and reaches its DLL with HostSession.send; the client reaches its own
 * DLL with its HostLink. The world track registers itself in WorldModule (with /cyberblock level).
 */
public class Cyberblock implements ModInitializer {
	public static final String ID = "cyberblock";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/**
	 * The client's own answer to {@link #hostActive} for client-side players (its HostState); the client entrypoint installs it
	 * (megaplan section 1.4).
	 */
	public static volatile Predicate<Player> clientHostActive = player -> false;

	/**
	 * Is this player a host whose Cyberpunk is On and streaming? On the server the player's session decides; on the client its own
	 * HostState (through {@link #clientHostActive}). For code that runs on both sides (for example PlayerMixin).
	 */
	public static boolean hostActive(final Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			HostSession session = HostSessions.of(serverPlayer);
			return session != null && session.active();
		}

		return clientHostActive.test(player);
	}

	@Override
	public void onInitialize() {
		// megaplan section 1.3: payload types (both sides), the server receivers and the sessions; first, so sessions end (and the
		// world track cleans up after them) before the world track detaches at server stop
		ServerNet.init();
		WorldModule.init();
		// /cyberblock survey [start|redo|stop|status]: the Atlas survey tour, asked of the issuing host's client (survey_cmd DOWN)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			var survey = Commands.literal("survey").executes(command -> survey(command.getSource(), "start"));
			for (String op : new String[] {"start", "redo", "stop", "status"}) {
				survey = survey.then(Commands.literal(op).executes(command -> survey(command.getSource(), op)));
			}

			dispatcher.register(Commands.literal("cyberblock").then(survey));
		});
		// /cyberblock guests and /cyberblock hosts (megaplan sections 1.4 and 1.6); /cyberblock test only with -Dcyberblock.devtools=true
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			NetCommands.register(dispatcher);
			if (DevCommands.enabled()) {
				DevCommands.register(dispatcher);
			}
		});
		// content as data (megaplan section 1.7)
		ContentLoader.init();
		LOG.info("cyberblock loaded");
	}

	/** The survey runs in the issuing host's Cyberpunk game: its client gets {"t":"survey_cmd","op":...} and answers in chat. */
	private static int survey(final CommandSourceStack source, final String op) {
		ServerPlayer player = source.getPlayer();
		HostSession session = player == null ? null : HostSessions.of(player);
		if (session == null || session.state() == HostSession.State.NONE) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.survey.no_host", "Only a player with a linked Cyberpunk game can run the Atlas survey"));
			return 0;
		}

		session.send("{\"t\":\"survey_cmd\",\"op\":\"" + op + "\"}");
		source.sendSuccess(() -> Component.translatableWithFallback("cyberblock.survey.asked", "Asked your Cyberpunk game: survey %s", op), false);
		return 1;
	}
}
