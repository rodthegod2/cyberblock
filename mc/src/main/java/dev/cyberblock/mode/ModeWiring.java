package dev.cyberblock.mode;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.holo.HoloHooks;
import dev.cyberblock.mode.downed.Downed;
import dev.cyberblock.mode.heat.ServerHeat;
import dev.cyberblock.mode.match.MatchWire;
import dev.cyberblock.mode.match.Matches;
import dev.cyberblock.mode.match.Pvp;
import dev.cyberblock.mode.overlord.OverlordHooks;
import dev.cyberblock.mode.pvphack.PvpHooks;
import dev.cyberblock.mode.spectate.CasterWire;
import dev.cyberblock.mode.spectate.Spectate;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The Phase 8 integrator's wiring (megaplan section 12): the packages were built in parallel, each behind replaceable hooks with a
 * fail-closed default; this installs 8M's matches into 8O's Overlord, 8X's player hacks and 8T's Downed and spectator mode, and 8T's
 * Server Heat into the Overlord's Auto-Director. Called once from Cyberblock.onInitialize after every Phase 8 init.
 */
public final class ModeWiring {
	private ModeWiring() {
	}

	public static void init() {
		// 8O: only a RUNNING match gets Overlord spawns and events; its zone bounds them; the Auto-Director reads the Heat
		OverlordHooks.running = Matches::running;
		OverlordHooks.area = Matches::area;
		OverlordHooks.heat = () -> (int) Math.round(ServerHeat.value());
		// 8X: a chip works on a player whose PvP toggle is on or who stands in a running match's zone; Ping outlines its team
		PvpHooks.use(Pvp::on, Matches::inWarZone, Matches::team, "8M (the PvP toggle, running match zones, match teams)");
		// 8T: revives between match teammates; the Match Terminal's spectators; match kills in the kill feed; the caster overlay's match
		Downed.teammates = Matches::teammates;
		Matches.spectate = Spectate::start;
		Matches.unspectate = Spectate::stop;
		Matches.feed = Spectate::feed;
		Spectate.matchView = ModeWiring::casterView;
		Cyberblock.LOG.info("modes: Phase 8 hooks wired (Overlord <- matches and Heat; player hacks <- PvP, war zones, teams; Downed, spectate <- matches)");
	}

	/** The caster overlay's view of the match the viewer is in (as a spectator from the Match Terminal), or null for the default view. */
	static Spectate.@Nullable MatchView casterView(final ServerPlayer viewer) {
		MatchWire.View v = Matches.viewFor(viewer);
		if (v == null) {
			return null;
		}

		var players = viewer.level().getServer().getPlayerList();
		Predicate<String> down = name -> {
			ServerPlayer p = players.getPlayerByName(name);
			return p != null && HoloHooks.downed.test(p);
		};
		List<CasterWire.Team> teams = v.teams().stream().map(t -> new CasterWire.Team(t.name(), t.colour(),
			t.members().stream().map(m -> new CasterWire.Member(m.name(), down.test(m.name()))).toList())).toList();
		List<CasterWire.Row> board = v.teams().stream().map(t -> new CasterWire.Row(t.name(), t.score())).toList();
		return new Spectate.MatchView(v.name(), v.time(), teams, "Score", board);
	}
}
