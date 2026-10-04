package dev.cyberblock.mode.match;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.holo.HoloLayers;
import dev.cyberblock.holo.HoloRules;
import dev.cyberblock.holo.HoloWire;
import dev.cyberblock.holo.Landmarks;
import dev.cyberblock.hub.MatchTerminalBlock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import dev.cyberblock.skin.CyberSkins;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The server's matches (megaplan 8-M1), server thread: the Match Terminal's requests ({@code act match}), the screen and HUD data
 * ({@code ui match}), one tick a second (time limits, capture points, scores), takedowns, PvP on and off for match players, the
 * Holo-Table hooks of 7-T1 (match, team colour, team bases, locked zones, the {@code matches} and {@code gangwars} layers) and Gang
 * Wars reputation. Matches live only while the server runs: a restart ends them.
 *
 * <p>Log lines: {@code match: <name> created <id> (<mode>, <district|x z>, r <r>, <s> s, pvp on|off, open|invite only)},
 * {@code match: <name> joined <id> as <role> (team <t>)}, {@code match: <name> left <id>}, {@code match: <id> started (<n> players,
 * <bases> spawns, <points> capture points)}, {@code match: <id> <team> took <point>}, {@code match: <id> ended (<result>; <scores>)},
 * {@code match: <id> reputation: attitude DOWN to <n> host(s) (Phase 0 e; the scripts log whether the game applied it)} or
 * {@code match: <id> reputation: no active Cyberpunk host on the teams (fallback: the standing stays on the Minecraft scoreboard)}.
 */
public final class Matches {
	/** How far from the terminal a player may be to create a match from its screen, and how long the screen counts as open. */
	private static final double TERMINAL_REACH = 8.0;
	private static final long TERMINAL_MS = 10L * 60L * 1000L;
	private static final Map<String, Match> matches = new LinkedHashMap<>();
	private static final Map<UUID, String> memberOf = new HashMap<>();
	/** Where and when each player last opened a Match Terminal. */
	private static final Map<UUID, Terminal> terminals = new HashMap<>();
	private static @Nullable ContentKind<MatchMode> kind;
	private static @Nullable MinecraftServer server;
	private static int nextId = 1;

	private record Terminal(BlockPos pos, long ms) {
	}

	private Matches() {
	}

	static void init() {
		kind = ContentKind.register(MatchMode.KIND, MatchMode::parse, false);
		ServerNet.onAct(MatchWire.T, Matches::act);
		MatchTerminalBlock.opener = Matches::openTerminal;
		HoloHooks.match = Matches::holoMatch;
		HoloHooks.teamColour = Matches::teamColour;
		HoloHooks.bases = Matches::bases;
		HoloHooks.lockedZone = (level, pos, player) -> level.dimension() == Level.OVERWORLD && lockedFor(pos, player.getUUID());
		HoloLayers.register("matches", (s, viewer) -> matchesLayer());
		HoloLayers.register("gangwars", (s, viewer) -> gangWarsLayer());
	}

	static void started(final MinecraftServer s) {
		server = s;
		NameTags.clear(s);
		Cyberblock.LOG.info("match: {} match mode(s) offered ({})", modes().size(), modesSource());
	}

	static void stopping() {
		for (Match m : List.copyOf(matches.values())) {
			if (m.state() != Match.State.ENDED) {
				finish(m, "server stop");
			}
		}

		matches.clear();
		memberOf.clear();
		terminals.clear();
		server = null;
	}

	// ---- queries other packages use (8-O1, 8-V1, 8-X1, 8-T1..3)

	/** The match the player is in (lobby or running), or null. */
	public static @Nullable Match of(final UUID player) {
		String id = memberOf.get(player);
		return id == null ? null : matches.get(id);
	}

	/** The player's membership (role and team) in its match, or null. */
	public static Match.@Nullable Member member(final UUID player) {
		Match m = of(player);
		return m == null ? null : m.member(player);
	}

	/** The player's team in its match for the roster (-1 none). */
	public static int team(final ServerPlayer player) {
		Match.Member m = member(player.getUUID());
		return m == null || !m.role().onTeam() ? -1 : m.team();
	}

	/** In a war zone: inside the zone of a running match the player is in (8-X1: chips work on players there). */
	public static boolean inWarZone(final ServerPlayer player) {
		Match m = of(player.getUUID());
		return m != null && m.state() == Match.State.RUNNING && player.level().dimension() == Level.OVERWORLD && m.zone.inside(player.getX(), player.getZ());
	}

	/** Every match on the server, in creation order. */
	public static List<Match> all() {
		return List.copyOf(matches.values());
	}

	/**
	 * A takedown of {@code victim} by {@code killer}: in a running kills match on opposite teams it scores for the killer's team.
	 * Called on death; 8-T2 (Downed) may call it when a downed player bleeds out instead.
	 */
	public static void takedown(final ServerPlayer killer, final ServerPlayer victim) {
		Match m = of(killer.getUUID());
		Match.Member k = m == null ? null : m.member(killer.getUUID());
		Match.Member v = m == null ? null : m.member(victim.getUUID());
		if (m == null || k == null || v == null || m.state() != Match.State.RUNNING || !k.role().onTeam() || !v.role().onTeam() || k.team() == v.team()) {
			return;
		}

		if (m.mode.scoring() == MatchMode.Scoring.KILLS) {
			m.score(k.team(), 1);
		}

		tell(m, Component.translatableWithFallback("cyberblock.match.takedown", "%s took down %s", k.name(), v.name()));
		changed(m);
	}

	/** The modes on offer: the loaded {@code match_mode} files, or the built-in two when none loaded. Sorted by id. */
	static Map<String, MatchMode> modes() {
		ContentKind<MatchMode> k = kind;
		Map<String, MatchMode> out = new TreeMap<>();
		if (k != null) {
			for (Map.Entry<Identifier, MatchMode> e : Content.all(k).entrySet()) {
				out.put(e.getKey().toString(), e.getValue());
			}
		}

		return out.isEmpty() ? new TreeMap<>(MatchMode.BUILT_IN) : out;
	}

	private static String modesSource() {
		ContentKind<MatchMode> k = kind;
		return k != null && !Content.all(k).isEmpty() ? "match_mode content" : "built in: no match_mode file loaded";
	}

	// ---- the terminal and its requests

	private static void openTerminal(final ServerPlayer player, final BlockPos pos) {
		terminals.put(player.getUUID(), new Terminal(pos.immutable(), System.currentTimeMillis()));
		send(player, true);
	}

	private static boolean atTerminal(final ServerPlayer player) {
		Terminal t = terminals.get(player.getUUID());
		return t != null && System.currentTimeMillis() - t.ms() <= TERMINAL_MS
			&& player.position().distanceToSqr(Vec3.atCenterOf(t.pos())) <= TERMINAL_REACH * TERMINAL_REACH;
	}

	private static void act(final ServerPlayer player, final JsonObject json) {
		MatchWire.Request r = MatchWire.request(json);
		if (r == null) {
			refuse(player, "cyberblock.match.refused.bad");
			return;
		}

		switch (r.op()) {
			case "open" -> send(player, false);
			case "create" -> create(player, r);
			case "join" -> join(player, r.id(), r.role() == null ? Match.Role.TEAMMATE : r.role(), r.team());
			case "leave" -> leave(player);
			case "start" -> start(player);
			case "end" -> end(player);
			case "invite" -> invite(player, r.name());
			default -> refuse(player, "cyberblock.match.refused.bad");
		}
	}

	private static void create(final ServerPlayer player, final MatchWire.Request r) {
		String why = MatchRules.createRefusal(CyberSkins.linked(player), of(player.getUUID()) != null, (int) matches.values().stream()
			.filter(m -> m.state() != Match.State.ENDED).count());
		MatchMode mode = modes().get(r.mode());
		if (why == null && !atTerminal(player)) {
			why = "cyberblock.match.refused.terminal";
		}

		if (why == null && mode == null) {
			why = "cyberblock.match.refused.bad";
		}

		if (why != null || mode == null || r.zone() == null) {
			refuse(player, why == null ? "cyberblock.match.refused.bad" : why);
			return;
		}

		String id = "m" + nextId++;
		Match m = new Match(id, r.mode(), mode, player.getUUID(), player.getPlainTextName(), r.zone(), r.pvp(), r.invite(), r.time(),
			System.currentTimeMillis());
		matches.put(id, m);
		Match.Zone z = m.zone;
		Cyberblock.LOG.info("match: {} created {} ({}, {}, r {}, {} s, pvp {}, {})", player.getPlainTextName(), id, mode.name(),
			z.district().isEmpty() ? String.format(Locale.ROOT, "%.0f %.0f", z.x(), z.z()) : z.district(), (int) z.r(), m.timeLimit, m.pvp ? "on" : "off",
			m.inviteOnly ? "invite only" : "open");
		join(player, id, Match.Role.TEAMMATE, -1);
		broadcastLobby(m);
	}

	private static void join(final ServerPlayer player, final String id, final Match.Role role, final int team) {
		Match m = matches.get(id);
		if (m == null) {
			refuse(player, "cyberblock.match.refused.no_match");
			return;
		}

		Match current = of(player.getUUID());
		String why = MatchRules.joinRefusal(m, player.getUUID(), CyberSkins.linked(player), role, current != null && current != m);
		if (why != null) {
			refuse(player, why);
			return;
		}

		Match.Member was = m.member(player.getUUID());
		Match.Member now = m.join(player.getUUID(), player.getPlainTextName(), role, CyberSkins.linked(player), team);
		memberOf.put(player.getUUID(), id);
		Cyberblock.LOG.info("match: {} joined {} as {} (team {})", now.name(), id, role.key(), now.team());
		if (m.state() == Match.State.RUNNING) {
			pvpIn(m, player, was, now);
		}

		tell(m, Component.translatableWithFallback("cyberblock.match.joined", "%s joined %s as %s", now.name(), m.mode.name(), roleName(role)));
		HostSessions.rosterChanged();
		changed(m);
	}

	private static void leave(final ServerPlayer player) {
		Match m = of(player.getUUID());
		if (m == null) {
			refuse(player, "cyberblock.match.refused.not_in");
			return;
		}

		remove(m, player.getUUID(), player);
		send(player, false);
	}

	/** Takes a member out (left, disconnected); a lobby whose creator left is closed. */
	private static void remove(final Match m, final UUID uuid, final @Nullable ServerPlayer player) {
		Match.Member member = m.leave(uuid);
		memberOf.remove(uuid);
		if (member == null) {
			return;
		}

		Cyberblock.LOG.info("match: {} left {}", member.name(), m.id);
		Boolean before = m.pvpBefore.remove(uuid);
		if (player != null && before != null) {
			Pvp.matchOver(player, before);
		}

		tell(m, Component.translatableWithFallback("cyberblock.match.left", "%s left the match", member.name()));
		HostSessions.rosterChanged();
		if (m.state() == Match.State.LOBBY && uuid.equals(m.creator)) {
			closeLobby(m, "creator left");
		} else {
			changed(m);
		}
	}

	private static void start(final ServerPlayer player) {
		Match m = of(player.getUUID());
		if (m == null) {
			refuse(player, "cyberblock.match.refused.not_in");
			return;
		}

		String why = MatchRules.startRefusal(m, player.getUUID());
		if (why != null) {
			refuse(player, why);
			return;
		}

		MatchLayout.Layout layout = MatchLayout.plan(m.zone, m.mode.teams(), m.mode.capturePoints(), spots(), 64.0);
		m.bases.addAll(layout.bases());
		m.points.addAll(layout.points());
		m.start(System.currentTimeMillis());
		MinecraftServer s = server;
		for (Match.Member member : m.members()) {
			ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(member.uuid());
			if (p != null) {
				pvpIn(m, p, null, member);
			}
		}

		Cyberblock.LOG.info("match: {} started ({} players, {} spawns, {} capture points)", m.id, m.count(Match.Role.TEAMMATE), m.bases.size(),
			m.points.size());
		tell(m, Component.translatableWithFallback("cyberblock.match.started",
			"%s started: spawn at your team's base from the Holo-Table. %s minutes on the clock", m.mode.name(), m.timeLimit / 60));
		changed(m);
	}

	/** A teammate of a running match gets PvP on (when the match has it); what it had before is kept for the end. */
	private static void pvpIn(final Match m, final ServerPlayer p, final Match.@Nullable Member was, final Match.Member now) {
		boolean fighter = now.role() == Match.Role.TEAMMATE;
		if (fighter && m.pvp && !m.pvpBefore.containsKey(now.uuid())) {
			m.pvpBefore.put(now.uuid(), Pvp.on(p));
			Pvp.matchOn(p);
		} else if (!fighter && was != null && was.role() == Match.Role.TEAMMATE) {
			Boolean before = m.pvpBefore.remove(now.uuid());
			if (before != null) {
				Pvp.matchOver(p, before);
			}
		}
	}

	private static void end(final ServerPlayer player) {
		Match m = of(player.getUUID());
		if (m == null) {
			refuse(player, "cyberblock.match.refused.not_in");
			return;
		}

		if (!player.getUUID().equals(m.creator)) {
			refuse(player, "cyberblock.match.refused.not_creator");
			return;
		}

		if (m.state() == Match.State.LOBBY) {
			closeLobby(m, "closed by its creator");
		} else {
			finish(m, "ended by its creator");
		}
	}

	private static void invite(final ServerPlayer player, final String name) {
		Match m = of(player.getUUID());
		MinecraftServer s = server;
		if (m == null || s == null) {
			refuse(player, "cyberblock.match.refused.not_in");
			return;
		}

		if (!player.getUUID().equals(m.creator)) {
			refuse(player, "cyberblock.match.refused.not_creator");
			return;
		}

		ServerPlayer target = s.getPlayerList().getPlayerByName(name);
		if (target == null) {
			refuse(player, "cyberblock.match.refused.no_player");
			return;
		}

		m.invite(target.getUUID());
		Component join = Component.translatableWithFallback("cyberblock.match.invite.join", "[Join]").withStyle(style -> style
			.withColor(0x55FF55).withClickEvent(new ClickEvent.RunCommand("/cyberblock match join " + m.id)));
		target.sendSystemMessage(Component.translatableWithFallback("cyberblock.match.invite", "%s invites you to %s (%s) ", player.getPlainTextName(),
			m.mode.name(), m.id).append(join));
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.match.invited", "Invited %s", target.getPlainTextName()), true);
		send(target, false);
		changed(m);
	}

	/** Commands: join from an invite's [Join] link, leave, status. */
	static int commandJoin(final ServerPlayer player, final String id, final String role) {
		Match.Role r = Match.Role.of(role);
		if (r == null) {
			refuse(player, "cyberblock.match.refused.bad");
			return 0;
		}

		join(player, id, r, -1);
		return of(player.getUUID()) != null ? 1 : 0;
	}

	static int commandLeave(final ServerPlayer player) {
		leave(player);
		return 1;
	}

	/** Dev command: the player right-clicked a Match Terminal where it stands. */
	static int devTerminal(final ServerPlayer player) {
		openTerminal(player, player.blockPosition());
		return 1;
	}

	/** Dev command: one act match from the player. */
	static int devAct(final ServerPlayer player, final String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			refuse(player, "cyberblock.match.refused.bad");
			return 0;
		}

		act(player, o);
		return 1;
	}

	/** One line per match for /cyberblock match. */
	static List<Component> status() {
		List<Component> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Match m : matches.values()) {
			StringBuilder scores = new StringBuilder();
			for (int t = 0; t < m.scores.length; t++) {
				scores.append(t == 0 ? "" : " - ").append(m.mode.teams().get(t).name()).append(' ').append(m.scores[t]);
			}

			out.add(Component.translatableWithFallback("cyberblock.match.status_line", "%s %s by %s: %s, %s players, %s s left, %s", m.id, m.mode.name(),
				m.creatorName, m.state().key(), m.members().size(), m.secondsLeft(now), scores.toString()));
		}

		return out;
	}

	// ---- the tick

	static void tick(final MinecraftServer s) {
		NameTags.tick(s);
		if (s.getTickCount() % 20 != 0) {
			return;
		}

		long now = System.currentTimeMillis();
		for (Match m : List.copyOf(matches.values())) {
			if (m.state() == Match.State.RUNNING) {
				capture(s, m);
				if (MatchRules.over(m, now)) {
					finish(m, now >= m.endsMs ? "time" : "score limit");
					continue;
				}

				for (Match.Member member : m.members()) {
					ServerPlayer p = s.getPlayerList().getPlayer(member.uuid());
					if (p != null) {
						send(p, false); // the HUD's clock and scores
					}
				}
			} else if (MatchRules.expired(m, now)) {
				if (m.state() == Match.State.LOBBY) {
					closeLobby(m, "nobody started it");
				} else {
					matches.remove(m.id);
				}
			}
		}
	}

	/** One second of Gang Wars: every capture point steps with the teammates standing in it; owned points score. */
	private static void capture(final MinecraftServer s, final Match m) {
		if (m.points.isEmpty()) {
			return;
		}

		int teams = m.mode.teams().size();
		boolean changed = false;
		for (Capture.Point p : m.points) {
			int[] counts = new int[teams];
			for (Match.Member member : m.members()) {
				ServerPlayer pl = member.role() == Match.Role.TEAMMATE ? s.getPlayerList().getPlayer(member.uuid()) : null;
				if (pl != null && pl.isAlive() && !pl.isSpectator() && pl.level().dimension() == Level.OVERWORLD && member.team() >= 0
					&& member.team() < teams && p.inside(pl.getX(), pl.getZ())) {
					counts[member.team()]++;
				}
			}

			int owner = p.owner();
			Capture.Change c = Capture.step(p, counts, 1.0, m.mode.captureSeconds());
			if (c == Capture.Change.CAPTURED) {
				String team = m.mode.teams().get(p.owner()).name();
				Cyberblock.LOG.info("match: {} {} took {}", m.id, team, p.name);
				tell(m, Component.translatableWithFallback("cyberblock.match.captured", "%s took %s", team, p.name));
				changed = true;
			} else if (c == Capture.Change.NEUTRALISED && owner >= 0) {
				tell(m, Component.translatableWithFallback("cyberblock.match.neutralised", "%s lost %s", m.mode.teams().get(owner).name(), p.name));
				changed = true;
			}

			if (p.owner() >= 0) {
				m.score(p.owner(), 1);
			}
		}

		if (changed) {
			changed(m);
		}
	}

	/** Ends a running match: the result, PvP back, reputation, the result stays listed a minute. */
	private static void finish(final Match m, final String why) {
		m.end(System.currentTimeMillis());
		MinecraftServer s = server;
		StringBuilder scores = new StringBuilder();
		for (int t = 0; t < m.scores.length; t++) {
			scores.append(t == 0 ? "" : ", ").append(m.mode.teams().get(t).name()).append(' ').append(m.scores[t]);
		}

		String result = m.winner() < 0 ? "draw" : m.mode.teams().get(m.winner()).name() + " win";
		Cyberblock.LOG.info("match: {} ended ({}, {}; {})", m.id, why, result, scores);
		tell(m, m.winner() < 0 ? Component.translatableWithFallback("cyberblock.match.draw", "%s is over: a draw (%s)", m.mode.name(), scores.toString())
			: Component.translatableWithFallback("cyberblock.match.won", "%s is over: %s win (%s)", m.mode.name(), m.mode.teams().get(m.winner()).name(),
				scores.toString()));
		for (Match.Member member : m.members()) {
			memberOf.remove(member.uuid());
			ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(member.uuid());
			Boolean before = m.pvpBefore.remove(member.uuid());
			if (p != null && before != null) {
				Pvp.matchOver(p, before);
			}
		}

		reputation(m);
		HostSessions.rosterChanged();
		changed(m);
	}

	private static void closeLobby(final Match m, final String why) {
		Cyberblock.LOG.info("match: {} closed ({})", m.id, why);
		tell(m, Component.translatableWithFallback("cyberblock.match.closed", "%s was closed before it started", m.mode.name()));
		List<Match.Member> members = List.copyOf(m.members());
		for (Match.Member member : members) {
			memberOf.remove(member.uuid());
		}

		matches.remove(m.id);
		HostSessions.rosterChanged();
		MinecraftServer s = server;
		if (s != null) {
			for (Match.Member member : members) {
				ServerPlayer p = s.getPlayerList().getPlayer(member.uuid());
				if (p != null) {
					send(p, false);
				}
			}
		}
	}

	/**
	 * Phase 0 (e): the linked hosts of a Gang Wars team get {@code attitude} DOWN ({@link Reputation}). Only an ACTIVE session's game
	 * can apply it (a host whose Cyberpunk is Off is skipped); whether 2.31 applied it is the scripts' log line (8S). With nobody to
	 * send to, the written fallback holds: the standing stays on the Minecraft scoreboard.
	 */
	private static void reputation(final Match m) {
		List<Reputation.Change> changes = Reputation.afterMatch(m.mode, m.members(), m.winner());
		if (changes.isEmpty()) {
			return;
		}

		int sent = 0;
		java.util.Set<UUID> hosts = new java.util.HashSet<>();
		for (Reputation.Change c : changes) {
			HostSession session = HostSessions.of(c.host());
			if (session != null && session.active()) {
				session.send(c.json());
				hosts.add(c.host());
				sent++;
			}
		}

		if (sent > 0) {
			Cyberblock.LOG.info("match: {} reputation: attitude DOWN to {} host(s), {} change(s) (Phase 0 e; the scripts log whether the game applied it)",
				m.id, hosts.size(), sent);
		} else {
			Cyberblock.LOG.info("match: {} reputation: no active Cyberpunk host on the teams (fallback: the standing stays on the Minecraft scoreboard)", m.id);
		}
	}

	// ---- players, events

	static void disconnected(final ServerPlayer player) {
		terminals.remove(player.getUUID());
		Match m = of(player.getUUID());
		if (m != null) {
			remove(m, player.getUUID(), player);
		}
	}

	static void died(final ServerPlayer victim, final @Nullable ServerPlayer killer) {
		if (killer != null && killer != victim) {
			takedown(killer, victim);
		}
	}

	// ---- the ui message

	/** A player's PvP toggle changed (Pvp): its screen and the screens of its match show it. */
	static void changed(final ServerPlayer player) {
		Match m = of(player.getUUID());
		if (m != null) {
			changed(m);
		} else {
			send(player, false);
		}
	}

	/** Something in the match changed: every member and every player who sees the lobby list gets the new state. */
	private static void changed(final Match m) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (m.member(p.getUUID()) != null || terminals.containsKey(p.getUUID()) || m.invited(p.getUUID())) {
				send(p, false);
			}
		}
	}

	/** A new lobby: everyone who has the terminal screen open sees it. */
	private static void broadcastLobby(final Match m) {
		changed(m);
	}

	private static void send(final ServerPlayer player, final boolean open) {
		ServerNet.ui(player, MatchWire.T, MatchWire.json(state(player, open)));
	}

	static MatchWire.State state(final ServerPlayer player, final boolean open) {
		UUID uuid = player.getUUID();
		Match mine = of(uuid);
		Match.Member me = mine == null ? null : mine.member(uuid);
		String why = MatchRules.createRefusal(CyberSkins.linked(player), mine != null, (int) matches.values().stream()
			.filter(m -> m.state() != Match.State.ENDED).count());
		List<MatchWire.Mode> modes = new ArrayList<>();
		for (Map.Entry<String, MatchMode> e : modes().entrySet()) {
			MatchMode mode = e.getValue();
			modes.add(new MatchWire.Mode(e.getKey(), mode.name(), mode.description(), mode.teams().stream()
				.map(t -> new MatchWire.Team(t.name(), t.colour(), 0, List.of())).toList(), mode.pvp(), mode.timeLimit(), mode.scoring().key(),
				mode.zoneRadius()));
		}

		long now = System.currentTimeMillis();
		List<MatchWire.View> views = new ArrayList<>();
		for (Match m : matches.values()) {
			views.add(view(m, uuid, now));
		}

		return new MatchWire.State(open, why == null, why == null ? "" : why, mine == null ? "" : mine.id, me == null ? -1 : me.team(),
			me == null ? "" : me.role().key(), Pvp.on(player), PvpRules.seconds(Pvp.lockLeft(player)), List.copyOf(modes), districts(), List.copyOf(views));
	}

	private static MatchWire.View view(final Match m, final UUID viewer, final long now) {
		List<MatchWire.Team> teams = new ArrayList<>();
		for (int t = 0; t < m.mode.teams().size(); t++) {
			int team = t;
			MatchMode.TeamSpec spec = m.mode.teams().get(t);
			teams.add(new MatchWire.Team(spec.name(), spec.colour(), m.scores[t], m.members().stream().filter(x -> x.role().onTeam() && x.team() == team)
				.map(x -> new MatchWire.Member(x.name(), x.role().key(), x.linked())).toList()));
		}

		List<MatchWire.Member> others = m.members().stream().filter(x -> !x.role().onTeam())
			.map(x -> new MatchWire.Member(x.name(), x.role().key(), x.linked())).toList();
		List<MatchWire.Point> points = m.points.stream().map(p -> new MatchWire.Point(p.name, p.x, p.z, p.r, p.owner(), p.holder(), p.progress())).toList();
		return new MatchWire.View(m.id, m.modeId, m.mode.name(), m.creatorName, m.state().key(), m.inviteOnly, m.invited(viewer), m.pvp, m.zone.x(),
			m.zone.z(), m.zone.r(), m.zone.district(), m.secondsLeft(now), m.winner(), List.copyOf(teams), others, points);
	}

	/** Districts from the landmark content: the centre of each district's landmarks and a radius that holds them (32..512). */
	static List<MatchWire.District> districts() {
		Map<String, List<MatchLayout.Spot>> by = new TreeMap<>();
		for (Landmarks.Landmark l : landmarks()) {
			if (!l.district().isEmpty()) {
				by.computeIfAbsent(l.district(), d -> new ArrayList<>()).add(new MatchLayout.Spot(l.name(), l.pos().x, l.pos().y, l.pos().z, l.spawn()));
			}
		}

		List<MatchWire.District> out = new ArrayList<>();
		for (Map.Entry<String, List<MatchLayout.Spot>> e : by.entrySet()) {
			double x = e.getValue().stream().mapToDouble(MatchLayout.Spot::x).average().orElse(0.0);
			double z = e.getValue().stream().mapToDouble(MatchLayout.Spot::z).average().orElse(0.0);
			double r = e.getValue().stream().mapToDouble(sp -> Math.hypot(sp.x() - x, sp.z() - z)).max().orElse(0.0) + 32.0;
			out.add(new MatchWire.District(e.getKey(), Math.round(x), Math.round(z), Math.clamp(Math.round(r), 32, (long) MatchRules.MAX_RADIUS)));
		}

		return List.copyOf(out);
	}

	private static List<Landmarks.Landmark> landmarks() {
		ContentKind<?> k = ContentKind.byId(Landmarks.KIND);
		List<Landmarks.Landmark> out = new ArrayList<>();
		if (k != null) {
			for (Object v : Content.all(k).values()) {
				if (v instanceof Landmarks.Landmark l) {
					out.add(l);
				}
			}
		}

		return out;
	}

	private static List<MatchLayout.Spot> spots() {
		return landmarks().stream().map(l -> new MatchLayout.Spot(l.name(), l.pos().x, l.pos().y, l.pos().z, l.spawn())).toList();
	}

	// ---- Holo-Table hooks (7-T1)

	private static HoloHooks.Match holoMatch(final ServerPlayer player) {
		Match m = of(player.getUUID());
		Match.Member me = m == null ? null : m.member(player.getUUID());
		if (m == null || me == null) {
			return HoloHooks.Match.NONE;
		}

		HoloRules.Role role = switch (me.role()) {
			case SPECTATOR -> HoloRules.Role.SPECTATOR;
			case OVERLORD -> HoloRules.Role.OVERLORD;
			default -> HoloRules.Role.PLAYER;
		};
		return new HoloHooks.Match(m.id, me.role().onTeam() ? me.team() : -1, role);
	}

	private static int teamColour(final ServerPlayer player) {
		Match m = of(player.getUUID());
		Match.Member me = m == null ? null : m.member(player.getUUID());
		return me == null || !me.role().onTeam() ? -1 : m.mode.teams().get(me.team()).colour();
	}

	private static List<HoloHooks.Base> bases(final ServerPlayer player) {
		Match m = of(player.getUUID());
		if (m == null || m.state() != Match.State.RUNNING) {
			return List.of();
		}

		List<HoloHooks.Base> out = new ArrayList<>();
		for (int i = 0; i < m.bases.size(); i++) {
			Match.Base b = m.bases.get(i);
			out.add(new HoloHooks.Base(m.id + "_base" + i, b.name(), new Vec3(b.x(), b.y(), b.z()), m.id, b.team()));
		}

		return out;
	}

	/** In the zone of a running match the player is not part of. */
	private static boolean lockedFor(final Vec3 pos, final UUID player) {
		for (Match m : matches.values()) {
			if (m.state() == Match.State.RUNNING && m.member(player) == null && m.zone.inside(pos.x, pos.z)) {
				return true;
			}
		}

		return false;
	}

	/** Every lobby and running match as a spot at its zone centre (weight 1 running, 0.5 lobby). */
	private static List<HoloWire.LayerItem> matchesLayer() {
		List<HoloWire.LayerItem> out = new ArrayList<>();
		for (Match m : matches.values()) {
			if (m.state() != Match.State.ENDED) {
				String label = m.mode.name() + (m.zone.district().isEmpty() ? "" : " (" + m.zone.district() + ")");
				out.add(new HoloWire.LayerItem(m.zone.x(), m.zone.z(), m.state() == Match.State.RUNNING ? 1.0F : 0.5F, 0xFFAA00,
					label.length() > HoloWire.NAME_MAX ? label.substring(0, HoloWire.NAME_MAX) : label));
			}
		}

		return out;
	}

	/** Gang Wars territory: every capture point of a running match in its owner's colour (grey when neutral), weight = the bar. */
	private static List<HoloWire.LayerItem> gangWarsLayer() {
		List<HoloWire.LayerItem> out = new ArrayList<>();
		for (Match m : matches.values()) {
			if (m.state() != Match.State.RUNNING) {
				continue;
			}

			for (Capture.Point p : m.points) {
				int colour = p.owner() >= 0 ? m.mode.teams().get(p.owner()).colour() : p.holder() >= 0 ? m.mode.teams().get(p.holder()).colour() : 0x909090;
				String label = p.name.length() > HoloWire.NAME_MAX ? p.name.substring(0, HoloWire.NAME_MAX) : p.name;
				out.add(new HoloWire.LayerItem(p.x, p.z, (float) Math.max(0.1, p.progress()), colour, label));
			}
		}

		return out;
	}

	// ---- messages

	private static void refuse(final ServerPlayer player, final String key) {
		String fallback = switch (key) {
			case "cyberblock.match.refused.terminal" -> "Create matches at a Match Terminal";
			case "cyberblock.match.refused.no_player" -> "No player of that name is online";
			default -> MatchRules.fallback(key);
		};
		player.sendSystemMessage(Component.translatableWithFallback(key, fallback), true);
		send(player, false);
	}

	/** A chat line to every online member. */
	private static void tell(final Match m, final Component text) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		for (Match.Member member : m.members()) {
			ServerPlayer p = s.getPlayerList().getPlayer(member.uuid());
			if (p != null) {
				p.sendSystemMessage(text);
			}
		}
	}

	private static Component roleName(final Match.Role role) {
		return Component.translatableWithFallback("cyberblock.match.role." + role.key(), switch (role) {
			case TEAMMATE -> "a teammate";
			case HACKER -> "the hacker in the Van";
			case SPECTATOR -> "a spectator";
			case OVERLORD -> "an overlord";
		});
	}
}
