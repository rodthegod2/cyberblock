package dev.cyberblock.skin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Megaplan 5-K1 (sections 1.12 and 1.13), the server side of the forced cyber skins. Each player's body (male, the default, or
 * female) is a persisted Fabric data attachment, set with {@code /cyberblock body male|female} and sent to every client in
 * {@code cyberblock:roster}, whose {@code linked} flag (a LINKED or ACTIVE host session) makes the clients draw that player in the
 * cyber skin. Linked players are also kept in the scoreboard team {@value #TEAM}, whose coloured {@code [V] } prefix vanilla
 * draws on the name tag, in chat and in the player list. The team is kept cosmetic ({@link SkinRules#makeCosmetic}): no seeing
 * each other's Optical Camo, friendly fire on.
 */
public final class CyberSkins {
	/** The V team; vanilla sends it to every client like any scoreboard team. */
	static final String TEAM = "cyberblock_v";
	static final Component PREFIX = Component.literal("[V] ").withColor(SkinRules.V_COLOUR);
	/** How often (server ticks) the V team follows the sessions. */
	private static final int TEAM_EVERY = 5;
	/** The player's body; kept through death and saved with the world. */
	public static final AttachmentType<String> BODY = AttachmentRegistry.<String>builder()
		.persistent(Codec.STRING)
		.copyOnDeath()
		.initializer(() -> SkinRules.MALE)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "body"));

	private CyberSkins() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(command()));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % TEAM_EVERY == 0) {
				syncTeam(server);
			}
		});
	}

	/** The player's body for the roster; male when unset or unreadable (a hand-edited save). */
	public static String body(final Player player) {
		String b = SkinRules.parse(player.getAttachedOrElse(BODY, SkinRules.MALE));
		return b == null ? SkinRules.MALE : b;
	}

	/** A player with a LINKED or ACTIVE host session: drawn in the cyber skin and tagged [V]. */
	public static boolean linked(final ServerPlayer player) {
		HostSession session = HostSessions.of(player);
		return session != null && session.state() != HostSession.State.NONE;
	}

	/** /cyberblock body (shows it) and /cyberblock body male|female (any player, for itself). */
	private static LiteralArgumentBuilder<CommandSourceStack> command() {
		return Commands.literal("cyberblock").then(Commands.literal("body")
			.executes(c -> show(c.getSource()))
			.then(Commands.literal(SkinRules.MALE).executes(c -> set(c.getSource(), SkinRules.MALE)))
			.then(Commands.literal(SkinRules.FEMALE).executes(c -> set(c.getSource(), SkinRules.FEMALE))));
	}

	private static Component name(final String body) {
		return SkinRules.FEMALE.equals(body)
			? Component.translatableWithFallback("cyberblock.body.female", "female V (Lucy, slim model)")
			: Component.translatableWithFallback("cyberblock.body.male", "male V (classic model)");
	}

	private static int show(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.body.players_only", "Only a player has a cyber skin body"));
			return 0;
		}

		// system messages: the world's send_command_feedback is off (CyberblockClient), which would hide sendSuccess
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.body.current",
			"Your cyber skin: %s. Change it with /cyberblock body male|female", name(body(player))));
		return 1;
	}

	private static int set(final CommandSourceStack source, final String body) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.body.players_only", "Only a player has a cyber skin body"));
			return 0;
		}

		player.setAttached(BODY, body);
		HostSessions.rosterChanged();
		Cyberblock.LOG.info("skin: {} body {}", player.getPlainTextName(), body);
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.body.set",
			"Your cyber skin is now %s; every player sees it while your Cyberpunk game is linked", name(body)));
		return 1;
	}

	/** The V team holds exactly the online linked players (except those in another team); created on first use. */
	private static void syncTeam(final MinecraftServer server) {
		ServerScoreboard board = server.getScoreboard();
		PlayerTeam found = board.getPlayerTeam(TEAM);
		if (found == null) {
			found = board.addPlayerTeam(TEAM);
			found.setDisplayName(Component.translatableWithFallback("cyberblock.team.v", "Linked V"));
		}

		PlayerTeam team = found;
		if (!PREFIX.equals(team.getPlayerPrefix())) {
			team.setPlayerPrefix(PREFIX);
		}
		if (SkinRules.makeCosmetic(team)) {
			Cyberblock.LOG.info("skin: V team set cosmetic (members cannot see each other's camo, friendly fire on)");
		}

		Set<String> linked = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (linked(p)) {
				linked.add(p.getScoreboardName());
			}
		}

		SkinRules.TeamPlan plan = SkinRules.teamPlan(linked, Set.copyOf(team.getPlayers()), name -> {
			PlayerTeam current = board.getPlayersTeam(name);
			return current != null && current != team;
		});
		plan.leave().forEach(name -> board.removePlayerFromTeam(name, team));
		plan.join().forEach(name -> board.addPlayerToTeam(name, team));
		if (!plan.empty()) {
			Cyberblock.LOG.info("skin: [V] tag on {} player(s) (+{} -{})", linked.size(), plan.join().size(), plan.leave().size());
		}
	}
}
