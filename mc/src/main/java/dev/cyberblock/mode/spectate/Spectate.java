package dev.cyberblock.mode.spectate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.holo.HoloModule;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.hub.Leaderboards;
import dev.cyberblock.mode.heat.ServerHeat;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

/**
 * Spectator and caster mode (megaplan 8-T3): a Minecraft-only player spectates from the Match Terminal (8M calls {@link #start} with
 * its match) or with {@code /cyberblock spectate}: vanilla spectator movement, the Holo-Table pins it may see on the caster overlay's
 * map, and the caster overlay ({@link CasterWire}, once a second): scoreboard, Server Heat, match timer, team lists and the kill feed.
 * {@code /cyberblock spectate stop} (or leaving) puts it back where it was, in its old game mode.
 *
 * <p>Not an escape hatch: the command is refused to a player of a running match (its Match Terminal decides), to a player hurt or
 * targeted by a mob in the last 10 s, falling, gliding, burning or in lava, and again within a minute of the last stop
 * ({@link SpectateRules}). Every pin (the SPECTATOR role, {@link HoloHooks#match}) only for a match spectator from the Match Terminal
 * or an operator; anyone else keeps the PLAYER privacy rules (a hidden pin stays hidden). Where it came from is a persistent player
 * attachment, so a crash or a killed server never leaves a player stuck in spectator: the next time it is online (and past the join
 * password), it is put back.
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
	/** How far a mob that targets the player counts as a fight (blocks). */
	static final double TARGETED_RANGE = 48.0;
	private static final KillFeed FEED = new KillFeed();
	private static final Map<UUID, Saved> watching = new HashMap<>();
	private static final Map<UUID, Long> hurtAt = new HashMap<>();
	private static final Map<UUID, Long> stoppedAt = new HashMap<>();
	private static @Nullable MinecraftServer server;

	/** Where a spectator came from, and whether it sees every pin (a match spectator or an operator). */
	record Saved(GameType mode, ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, @Nullable String match, boolean seesAll) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
			GameType.CODEC.fieldOf("mode").forGetter(Saved::mode),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Saved::dimension),
			Vec3.CODEC.fieldOf("pos").forGetter(Saved::pos),
			Codec.FLOAT.fieldOf("yaw").forGetter(Saved::yRot),
			Codec.FLOAT.fieldOf("pitch").forGetter(Saved::xRot),
			Codec.STRING.optionalFieldOf("match").forGetter(s -> Optional.ofNullable(s.match())),
			Codec.BOOL.fieldOf("sees_all").forGetter(Saved::seesAll)
		).apply(i, (mode, dimension, pos, yaw, pitch, match, all) -> new Saved(mode, dimension, pos, yaw, pitch, match.orElse(null), all)));
	}

	/** Where a spectator goes back to (megaplan "no soft-locks": saved with the player, so it survives a crash). */
	static final AttachmentType<Saved> RETURN = AttachmentRegistry.<Saved>builder()
		.persistent(Saved.CODEC)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "spectate_return"));

	/** Wraps the Holo-Table's match hook: a match spectator or an operator watching is a SPECTATOR (sees every pin). */
	private record SpectatorRole(Function<ServerPlayer, HoloHooks.Match> inner) implements Function<ServerPlayer, HoloHooks.Match> {
		@Override
		public HoloHooks.Match apply(final ServerPlayer player) {
			HoloHooks.Match m = this.inner.apply(player);
			Saved s = watching.get(player.getUUID());
			return s == null || !s.seesAll() ? m : new HoloHooks.Match(s.match() != null ? s.match() : m.id(), -1, HoloRules.Role.SPECTATOR);
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
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayer p && server != null) {
				hurtAt.put(p.getUUID(), (long) server.getTickCount());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			stop(handler.getPlayer());
			hurtAt.remove(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			FEED.clear();
			watching.clear();
			hurtAt.clear();
			stoppedAt.clear();
			// after every package's init: whoever set the match hook (8M), a spectator's role wraps it
			if (!(HoloHooks.match instanceof SpectatorRole)) {
				HoloHooks.match = new SpectatorRole(HoloHooks.match);
			}

			Cyberblock.LOG.info("spectate: ready (/cyberblock spectate: not in a match, a fight, a fall or a fire, a minute apart; every pin for "
				+ "match spectators and operators; the way back is saved with the player; caster overlay once a second)");
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

	private static SpectateRules.@Nullable Refusal refusal(final ServerPlayer p, final @Nullable String match) {
		long now = p.level().getServer().getTickCount();
		HostSession session = HostSessions.of(p);
		HoloHooks.Match m = HoloHooks.match.apply(p);
		Long hurt = hurtAt.get(p.getUUID()), stopped = stoppedAt.get(p.getUUID());
		boolean targeted = !p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(TARGETED_RANGE), mob -> mob.getTarget() == p).isEmpty();
		boolean unsafe = !p.onGround() && p.fallDistance > 1.0 || p.isFallFlying() || p.isOnFire() || p.isInLava();
		return SpectateRules.refusal(session != null && session.state() != HostSession.State.NONE, HoloHooks.downed.test(p), watching.containsKey(p.getUUID()),
			m.id() != null && m.role() == HoloRules.Role.PLAYER, match != null, hurt == null ? Long.MAX_VALUE : now - hurt, targeted, unsafe,
			stopped == null ? Long.MAX_VALUE : now - stopped);
	}

	/**
	 * Starts spectating for 8M's Match Terminal: {@code match} is the match it watches (it then sees every pin); null starts it as the
	 * command does for a non-operator. False, and the player told why, when refused.
	 */
	public static boolean start(final ServerPlayer p, final @Nullable String match) {
		return start(p, match, false);
	}

	/** {@code operator}: the command's sender is an operator (it sees every pin). */
	private static boolean start(final ServerPlayer p, final @Nullable String match, final boolean operator) {
		SpectateRules.Refusal no = refusal(p, match);
		if (no != null) {
			p.sendSystemMessage(Component.translatableWithFallback(no.key, no.english));
			return false;
		}

		Saved saved = new Saved(p.gameMode(), p.level().dimension(), p.position(), p.getYRot(), p.getXRot(), match, match != null || operator);
		watching.put(p.getUUID(), saved);
		p.setAttached(RETURN, saved);
		p.setGameMode(GameType.SPECTATOR);
		p.sendSystemMessage(saved.seesAll()
			? Component.translatableWithFallback("cyberblock.spectate.on", "Spectating: fly anywhere, every pin is on the caster map. "
				+ "/cyberblock spectate stop to come back.")
			: Component.translatableWithFallback("cyberblock.spectate.on_player", "Spectating: fly anywhere; the caster map shows the pins "
				+ "you could see as a player. /cyberblock spectate stop to come back."));
		Cyberblock.LOG.info("spectate: {} started{} ({})", p.getPlainTextName(), match == null ? "" : " (match " + match + ")",
			saved.seesAll() ? "every pin" : "player pins");
		send(p);
		return true;
	}

	/** Stops spectating: back where it was, in its old game mode. False when it was not spectating. */
	public static boolean stop(final ServerPlayer p) {
		Saved s = watching.remove(p.getUUID());
		if (s == null) {
			return false;
		}

		goBack(p, s);
		stoppedAt.put(p.getUUID(), (long) p.level().getServer().getTickCount());
		ServerNet.ui(p, CasterWire.T, CasterWire.write(CasterWire.View.OFF));
		Cyberblock.LOG.info("spectate: {} stopped", p.getPlainTextName());
		return true;
	}

	private static void goBack(final ServerPlayer p, final Saved s) {
		p.removeAttached(RETURN);
		p.setGameMode(s.mode());
		ServerLevel level = p.level().getServer().getLevel(s.dimension());
		if (level != null) {
			p.teleportTo(level, s.pos().x, s.pos().y, s.pos().z, Set.of(), s.yRot(), s.xRot(), false);
		}
	}

	private static int command(final CommandSourceStack source, final boolean on) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.spectate.players_only", "Only players can spectate"));
			return 0;
		}

		if (on) {
			return start(p, null, Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) ? 1 : 0;
		}

		if (!stop(p)) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.spectate.not", "You are not spectating"));
			return 0;
		}

		p.sendSystemMessage(Component.translatableWithFallback("cyberblock.spectate.off", "Back in the game"));
		return 1;
	}

	private static void tick(final MinecraftServer s) {
		if (s.getTickCount() % 20 != 0) {
			return;
		}

		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (watching.containsKey(p.getUUID())) {
				send(p);
				continue;
			}

			// left in spectator by a crash or a killed server: back where it was, once past the join password
			Saved left = p.getAttached(RETURN);
			if (left != null && !JoinLock.locked(p)) {
				goBack(p, left);
				p.sendSystemMessage(Component.translatableWithFallback("cyberblock.spectate.restored", "The server stopped while you were "
					+ "spectating: you are back where you started"));
				Cyberblock.LOG.info("spectate: {} put back after a server stop while spectating", p.getPlainTextName());
			}
		}
	}

	private static void send(final ServerPlayer p) {
		ServerNet.ui(p, CasterWire.T, CasterWire.write(view(p)));
	}

	/** The overlay for one spectator: the match hook's view, else the default (vanilla teams, the Chaos board), and its pins. */
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
			m.board(), feed, pins(s, viewer));
	}

	private static HoloRules.Who who(final ServerPlayer p) {
		HoloHooks.Match m = HoloHooks.match.apply(p);
		return new HoloRules.Who(p.getUUID(), m.id(), m.team(), m.role(), Boolean.TRUE.equals(p.getAttachedOrElse(HoloModule.HIDE_PIN, false)));
	}

	/** The Holo-Table pins this spectator may see (the table's privacy rules for its role), nearest first; none outside Night City. */
	static List<CasterWire.Pin> pins(final MinecraftServer s, final ServerPlayer viewer) {
		if (viewer.level() != s.overworld()) {
			return List.of();
		}

		HoloRules.Who v = who(viewer);
		List<ServerPlayer> seen = new ArrayList<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p != viewer && p.level() == s.overworld() && !watching.containsKey(p.getUUID()) && !JoinLock.locked(p)
				&& HoloRules.pinVisible(v, who(p), HoloHooks.revealed.test(viewer, p))) {
				seen.add(p);
			}
		}

		seen.sort(Comparator.comparingDouble(p -> p.distanceToSqr(viewer)));
		return seen.stream().limit(CasterWire.PINS)
			.map(p -> new CasterWire.Pin(p.getPlainTextName(), p.getX(), p.getZ(), HoloHooks.teamColour.applyAsInt(p), HoloHooks.downed.test(p))).toList();
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
