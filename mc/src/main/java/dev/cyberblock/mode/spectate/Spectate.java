package dev.cyberblock.mode.spectate;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.hub.Leaderboards;
import dev.cyberblock.mode.heat.ServerHeat;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

/**
 * Spectator and caster mode (megaplan 8-T3): a Minecraft-only player spectates from the Match Terminal (8M calls {@link #start}) or
 * with {@code /cyberblock spectate}: vanilla spectator movement, every Holo-Table pin visible (its role is SPECTATOR for
 * {@link HoloHooks#match}), and the caster overlay ({@link CasterWire}, once a second): scoreboard, Server Heat, match timer, team lists
 * and the kill feed. {@code /cyberblock spectate stop} (or leaving) puts it back where it was, in its old game mode.
 *
 * <p>Hooks for 8M: {@link #matchView} (title, timer, teams and board of the match a spectator watches; the default reads vanilla
 * scoreboard teams and the Chaos leaderboard) and {@link #feed} (match kills, captures). Server thread.
 */
public final class Spectate {
	/** The match a spectator watches, for the overlay; null: none (the default view). */
	public record MatchView(String title, int secondsLeft, List<CasterWire.Team> teams, String boardTitle, List<CasterWire.Row> board) {
	}

	/** 8M: the view of the match {@code viewer} watches, or null to use the default view. */
	public static volatile Function<ServerPlayer, @Nullable MatchView> matchView = viewer -> null;
	private static final KillFeed FEED = new KillFeed();
	private static final Map<UUID, Saved> watching = new HashMap<>();
	private static @Nullable MinecraftServer server;

	/** Where a spectator came from. */
	private record Saved(GameType mode, ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, @Nullable String match) {
	}

	/** Wraps the Holo-Table's match hook: a spectator watching is a SPECTATOR (sees every pin). */
	private record SpectatorRole(Function<ServerPlayer, HoloHooks.Match> inner) implements Function<ServerPlayer, HoloHooks.Match> {
		@Override
		public HoloHooks.Match apply(final ServerPlayer player) {
			HoloHooks.Match m = this.inner.apply(player);
			Saved s = watching.get(player.getUUID());
			return s == null ? m : new HoloHooks.Match(s.match() != null ? s.match() : m.id(), -1, HoloRules.Role.SPECTATOR);
		}
	}

	private Spectate() {
	}

	/** Common init, once (Cyberblock.onInitialize). */
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("spectate").executes(c -> command(c.getSource(), true))
				.then(Commands.literal("stop").executes(c -> command(c.getSource(), false))))));
		ServerTickEvents.END_SERVER_TICK.register(Spectate::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> stop(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			FEED.clear();
			watching.clear();
			// after every package's init: whoever set the match hook (8M), a spectator's role wraps it
			if (!(HoloHooks.match instanceof SpectatorRole)) {
				HoloHooks.match = new SpectatorRole(HoloHooks.match);
			}

			Cyberblock.LOG.info("spectate: ready (/cyberblock spectate; the Match Terminal through Spectate.start; caster overlay once a second)");
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				stop(p);
			}

			server = null;
		});
	}

	/** Adds a kill feed line (who may be empty). Any phase may call it; ignored with no server. */
	public static void feed(final String who, final String verb, final String whom) {
		MinecraftServer s = server;
		if (s != null) {
			FEED.push(who, verb, whom, s.getTickCount());
		}
	}

	public static boolean spectating(final UUID player) {
		return watching.containsKey(player);
	}

	/**
	 * Why this player may not spectate (a lang key's English), or null when it may: Minecraft-only players only (a linked host's Steve
	 * belongs to its V), not while down, not twice.
	 */
	static @Nullable Component refusal(final ServerPlayer p) {
		HostSession session = HostSessions.of(p);
		if (session != null && session.state() != HostSession.State.NONE) {
			return Component.translatableWithFallback("cyberblock.spectate.host", "Spectating is for Minecraft-only players: your Cyberpunk link is on");
		}

		if (HoloHooks.downed.test(p)) {
			return Component.translatableWithFallback("cyberblock.spectate.down", "You cannot spectate while you are down");
		}

		if (watching.containsKey(p.getUUID())) {
			return Component.translatableWithFallback("cyberblock.spectate.already", "You are already spectating (/cyberblock spectate stop)");
		}

		return null;
	}

	/**
	 * Starts spectating (8M's Match Terminal passes the match id; null for none). False, and the player told why, when refused.
	 */
	public static boolean start(final ServerPlayer p, final @Nullable String match) {
		Component no = refusal(p);
		if (no != null) {
			p.sendSystemMessage(no);
			return false;
		}

		watching.put(p.getUUID(), new Saved(p.gameMode(), p.level().dimension(), p.position(), p.getYRot(), p.getXRot(), match));
		p.setGameMode(GameType.SPECTATOR);
		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.spectate.on", "Spectating: fly anywhere, every pin is visible. "
			+ "/cyberblock spectate stop to come back."));
		Cyberblock.LOG.info("spectate: {} started{}", p.getPlainTextName(), match == null ? "" : " (match " + match + ")");
		send(p);
		return true;
	}

	/** Stops spectating: back where it was, in its old game mode. False when it was not spectating. */
	public static boolean stop(final ServerPlayer p) {
		Saved s = watching.remove(p.getUUID());
		if (s == null) {
			return false;
		}

		p.setGameMode(s.mode());
		MinecraftServer srv = p.level().getServer();
		ServerLevel level = srv.getLevel(s.dimension());
		if (level != null) {
			p.teleportTo(level, s.pos().x, s.pos().y, s.pos().z, Set.of(), s.yRot(), s.xRot(), false);
		}

		ServerNet.ui(p, CasterWire.T, CasterWire.write(CasterWire.View.OFF));
		Cyberblock.LOG.info("spectate: {} stopped", p.getPlainTextName());
		return true;
	}

	private static int command(final CommandSourceStack source, final boolean on) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.spectate.players_only", "Only players can spectate"));
			return 0;
		}

		if (on) {
			return start(p, null) ? 1 : 0;
		}

		if (!stop(p)) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.spectate.not", "You are not spectating"));
			return 0;
		}

		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.spectate.off", "Back in the game"));
		return 1;
	}

	private static void tick(final MinecraftServer s) {
		if (watching.isEmpty() || s.getTickCount() % 20 != 0) {
			return;
		}

		for (UUID u : List.copyOf(watching.keySet())) {
			ServerPlayer p = s.getPlayerList().getPlayer(u);
			if (p != null) {
				send(p);
			}
		}
	}

	private static void send(final ServerPlayer p) {
		ServerNet.ui(p, CasterWire.T, CasterWire.write(view(p)));
	}

	/** The overlay for one spectator: the match hook's view, else the default (vanilla teams, the Chaos board). */
	static CasterWire.View view(final ServerPlayer viewer) {
		MinecraftServer s = viewer.level().getServer();
		long now = s.getTickCount();
		List<CasterWire.Feed> feed = FEED.recent(now).stream()
			.map(e -> new CasterWire.Feed(e.who(), e.verb(), e.whom(), (int) ((now - e.tick()) / 20))).toList();
		MatchView m = null;
		try {
			m = matchView.apply(viewer);
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("spectate: the match view failed: {}", e.toString());
		}

		if (m == null) {
			m = defaultView(s);
		}

		return new CasterWire.View(true, m.title(), m.secondsLeft(), (int) Math.round(ServerHeat.value()), ServerHeat.tier(), m.teams(), m.boardTitle(),
			m.board(), feed);
	}

	/** Vanilla scoreboard teams with players online (the rest as "Players"), and the Chaos leaderboard's top. */
	static MatchView defaultView(final MinecraftServer s) {
		ServerScoreboard board = s.getScoreboard();
		List<CasterWire.Team> teams = new ArrayList<>();
		List<CasterWire.Member> loose = new ArrayList<>();
		Map<String, ServerPlayer> online = new HashMap<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!watching.containsKey(p.getUUID())) {
				online.put(p.getScoreboardName(), p);
			}
		}

		for (PlayerTeam team : board.getPlayerTeams()) {
			List<CasterWire.Member> members = new ArrayList<>();
			for (String name : team.getPlayers()) {
				ServerPlayer p = online.get(name);
				if (p != null) {
					members.add(new CasterWire.Member(p.getPlainTextName(), HoloHooks.downed.test(p)));
				}
			}

			if (!members.isEmpty()) {
				members.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
				teams.add(new CasterWire.Team(team.getDisplayName().getString(), team.getColor().map(TeamColor::rgb).orElse(0xFFFFFF), members));
			}
		}

		for (ServerPlayer p : online.values()) {
			if (p.getTeam() == null) {
				loose.add(new CasterWire.Member(p.getPlainTextName(), HoloHooks.downed.test(p)));
			}
		}

		if (!loose.isEmpty()) {
			loose.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
			teams.add(new CasterWire.Team("Players", 0xFFFFFF, loose));
		}

		List<CasterWire.Row> rows = new ArrayList<>();
		Objective chaos = board.getObjective(Leaderboards.CHAOS);
		if (chaos != null) {
			board.listPlayerScores(chaos).stream().filter(e -> !e.isHidden())
				.sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner))
				.limit(5).forEach(e -> rows.add(new CasterWire.Row(e.owner(), e.value())));
		}

		return new MatchView("", -1, teams, rows.isEmpty() ? "" : Leaderboards.title(Leaderboards.CHAOS, "").getString(), rows);
	}
}
