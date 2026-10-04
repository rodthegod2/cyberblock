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
		// /cyberblock debug aim|view [on|off] (plan Q-B3): the Cyberblock client handles these before they are sent; the server only
		// has them for completion and to answer a client without the mod (permission level 0)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			var debug = Commands.literal("debug");
			for (String flag : new String[] {"aim", "view"}) {
				var node = Commands.literal(flag).executes(command -> clientOnly(command.getSource()));
				for (String state : new String[] {"on", "off"}) {
					node = node.then(Commands.literal(state).executes(command -> clientOnly(command.getSource())));
				}

				debug = debug.then(node);
			}

			dispatcher.register(Commands.literal("cyberblock").then(debug)
				.then(Commands.literal("settings").executes(command -> clientOnly(command.getSource()))));
		});
		// /cyberblock outline [erase on|off] (plan Q-B5): operators and the singleplayer owner
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("cyberblock").then(Commands.literal("outline").requires(OutlineRules::mayChange)
				.executes(command -> outline(command.getSource(), null))
				.then(Commands.literal("erase")
					.then(Commands.literal("on").executes(command -> outline(command.getSource(), true)))
					.then(Commands.literal("off").executes(command -> outline(command.getSource(), false)))))));
		// /cyberblock worldcheck [on|off]: a dedicated server refuses hosts on a world that is not the Night City type; spawn platform
		dev.cyberblock.net.WorldCheck.init();
		// /cyberblock lan offline on|off: the owner lets offline guests join a world opened to LAN (off at every world start)
		dev.cyberblock.net.LanAccess.init();
		// content as data (megaplan section 1.7)
		ContentLoader.init();
		// Phase 3E: time and weather from the primary host, /cyberblock meetup, the Offline Builds summary and /cyberblock changes
		dev.cyberblock.env.EnvSync.init(); dev.cyberblock.meetup.MeetUp.init(); dev.cyberblock.env.OfflineBuilds.init();
		// dedicated-server admin bits (megaplan 2-M1): startup line, whitelist warning, /cyberblock status and hosts add|remove
		dev.cyberblock.net.admin.ServerAdmin.init();
		LOG.info("cyberblock loaded");
	}

	/** /cyberblock outline (on null: the status) and /cyberblock outline erase on|off. */
	private static int outline(final CommandSourceStack source, final Boolean on) {
		if (on != null && OutlineRules.setCreativeErases(on)) {
			LOG.info("outline: permanent erase {} (by {})", on ? "on" : "off", source.getTextName());
		}

		boolean now = OutlineRules.creativeErases();
		// a system message: the world's send_command_feedback is off (CyberblockClient), which would hide sendSuccess
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.outline.erase." + (now ? "on" : "off"), now
			? "Creative can erase the replica permanently: ON (broken outline blocks stay until the probes see the cell again)"
			: "Creative can erase the replica permanently: OFF (broken outline blocks come back in about 10 s)"));
		if (OutlineRules.creativeErasesSource() == OutlineRules.Source.SERVER_JSON) {
			// megaplan 1.13: server.json wins when it sets the value explicitly; the world's setting is kept for when it does not
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.outline.erase.forced",
				"Set by config/cyberblock/server.json (creativeErasesReplica: true); change it there and restart to use this world's setting"));
		}

		return 1;
	}

	/** A client-side command reached the server: the player's client does not have the Cyberblock mod. */
	private static int clientOnly(final CommandSourceStack source) {
		source.sendFailure(Component.translatableWithFallback("cyberblock.debug.client_only", "This needs the Cyberblock mod on your client"));
		return 0;
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
