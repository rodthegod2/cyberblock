package dev.cyberblock.mode.match;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.skin.CyberSkins;
import dev.cyberblock.skin.SkinRules;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

/**
 * Name tags for PvP and match teams (megaplan 8-M1), drawn by vanilla from scoreboard teams so every client shows them: a player
 * with PvP on has a <b>red name</b> and a red crossed-swords mark; a player on a match team has its team name in the team colour
 * before the name; a linked player keeps its {@code [V] } prefix. One scoreboard team per look ({@code cbm_pvp}, {@code cbm_pvp_v},
 * {@code cbm_<match>_<team>[p][v]}); they are cosmetic like the V team (friendly fire on, so {@link Pvp} alone decides; no seeing
 * each other's camo). A player in a team that is not Cyberblock's (an operator's) is left there. When a player needs none of
 * these looks it leaves its {@code cbm_} team and CyberSkins puts a linked player back into the V team. Empty {@code cbm_} teams
 * are removed; leftovers of an earlier run go at server start. Server thread.
 */
final class NameTags {
	static final String PREFIX = "cbm_";
	private static final String V_TEAM = "cyberblock_v";
	private static final int EVERY = 10;
	private static final Component SWORDS = Component.literal(" ⚔").withColor(0xFF4040);

	private NameTags() {
	}

	/** The scoreboard team a player's look needs, or null for none. Pure. */
	static @Nullable String teamName(final @Nullable String match, final int team, final boolean pvp, final boolean linked) {
		if (match == null || team < 0) {
			return pvp ? PREFIX + "pvp" + (linked ? "_v" : "") : null;
		}

		return PREFIX + match + "_" + team + (pvp ? "p" : "") + (linked ? "v" : "");
	}

	/** The vanilla team colour closest to an RGB colour (the name's colour when PvP is off). */
	static TeamColor nearest(final int rgb) {
		TeamColor best = TeamColor.WHITE;
		long bestD = Long.MAX_VALUE;
		for (TeamColor c : TeamColor.VALUES) {
			int r = (c.rgb() >> 16 & 0xFF) - (rgb >> 16 & 0xFF), g = (c.rgb() >> 8 & 0xFF) - (rgb >> 8 & 0xFF), b = (c.rgb() & 0xFF) - (rgb & 0xFF);
			long d = (long) r * r + (long) g * g + (long) b * b;
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}

		return best;
	}

	static void tick(final MinecraftServer server) {
		if (server.getTickCount() % EVERY == 0) {
			sync(server);
		}
	}

	/** Removes every cbm_ team (a fresh start: no match survives a restart). */
	static void clear(final MinecraftServer server) {
		ServerScoreboard board = server.getScoreboard();
		for (PlayerTeam t : List.copyOf(board.getPlayerTeams())) {
			if (t.getName().startsWith(PREFIX)) {
				board.removePlayerTeam(t);
			}
		}
	}

	private static void sync(final MinecraftServer server) {
		ServerScoreboard board = server.getScoreboard();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String name = p.getScoreboardName();
			PlayerTeam current = board.getPlayersTeam(name);
			if (current != null && !current.getName().startsWith(PREFIX) && !current.getName().equals(V_TEAM)) {
				continue; // an operator's team wins, as for the V team
			}

			Match m = Matches.of(p.getUUID());
			Match.Member member = m == null ? null : m.member(p.getUUID());
			int team = member != null && member.role().onTeam() ? member.team() : -1;
			boolean pvp = Pvp.on(p), linked = CyberSkins.linked(p);
			String wanted = teamName(m == null ? null : m.id, team, pvp, linked);
			if (wanted == null) {
				if (current != null && current.getName().startsWith(PREFIX)) {
					board.removePlayerFromTeam(name, current);
				}

				continue;
			}

			if (current != null && current.getName().equals(wanted)) {
				continue;
			}

			PlayerTeam target = board.getPlayerTeam(wanted);
			if (target == null) {
				target = board.addPlayerTeam(wanted);
				look(target, team >= 0 && m != null ? m.mode.teams().get(team) : null, pvp, linked);
			}

			board.addPlayerToTeam(name, target);
			Cyberblock.LOG.debug("match: {} name tag {}", name, wanted);
		}

		for (PlayerTeam t : List.copyOf(board.getPlayerTeams())) {
			if (t.getName().startsWith(PREFIX) && t.getPlayers().isEmpty()) {
				board.removePlayerTeam(t);
			}
		}
	}

	private static void look(final PlayerTeam t, final MatchMode.@Nullable TeamSpec spec, final boolean pvp, final boolean linked) {
		MutableComponent prefix = Component.empty();
		if (linked) {
			prefix.append(Component.literal("[V] ").withColor(SkinRules.V_COLOUR));
		}

		if (spec != null) {
			prefix.append(Component.literal("[" + spec.name() + "] ").withColor(spec.colour()));
		}

		t.setDisplayName(Component.literal(t.getName()));
		t.setPlayerPrefix(prefix);
		t.setPlayerSuffix(pvp ? SWORDS : Component.empty());
		t.setColor(pvp ? Optional.of(TeamColor.RED) : spec != null ? Optional.of(nearest(spec.colour())) : Optional.empty());
		t.setAllowFriendlyFire(true);
		t.setSeeFriendlyInvisibles(false);
		t.setNameTagVisibility(Team.Visibility.ALWAYS);
		t.setCollisionRule(Team.CollisionRule.ALWAYS);
	}
}
