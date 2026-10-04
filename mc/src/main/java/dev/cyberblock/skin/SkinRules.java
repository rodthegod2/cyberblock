package dev.cyberblock.skin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 5-K1, the pure rules of the cyber skins (CyberSkins applies them on the server): the body names, and who joins and
 * who leaves the scoreboard team whose coloured {@code [V] } prefix vanilla draws on linked players' name tags. A linked player
 * already in another team (an operator's team, a Phase 8 match team) is left there: one team per player, and the other team
 * wins; the player list marker still shows it.
 */
public final class SkinRules {
	public static final String MALE = "male";
	public static final String FEMALE = "female";
	/** The [V] prefix and player list marker colour (a Night City yellow). */
	public static final int V_COLOUR = 0xFCEE0A;

	/** Scoreboard names to take out of the V team and to put into it, in a stable order. */
	record TeamPlan(List<String> leave, List<String> join) {
		boolean empty() {
			return this.leave.isEmpty() && this.join.isEmpty();
		}
	}

	private SkinRules() {
	}

	/** "male" or "female" (any case, trimmed), else null. */
	public static @Nullable String parse(final @Nullable String body) {
		String b = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
		return MALE.equals(b) || FEMALE.equals(b) ? b : null;
	}

	/**
	 * @param linked the scoreboard names of the online linked players
	 * @param members the V team's members now (the saved scoreboard may hold names from an earlier run)
	 * @param inOtherTeam whether a name is in a team other than the V team
	 */
	static TeamPlan teamPlan(final Set<String> linked, final Collection<String> members, final Predicate<String> inOtherTeam) {
		List<String> leave = members.stream().filter(name -> !linked.contains(name)).sorted().toList();
		List<String> join = new ArrayList<>();
		for (String name : linked.stream().sorted().toList()) {
			if (!members.contains(name) && !inOtherTeam.test(name)) {
				join.add(name);
			}
		}

		return new TeamPlan(leave, List.copyOf(join));
	}

	/**
	 * Keeps the V team cosmetic (a prefix, not an alliance): members do not see each other through invisibility (Optical Camo is
	 * the INVISIBILITY effect), can hurt each other, and keep name tags and collision as without a team. Vanilla's team defaults
	 * would let them see each other's camo. Sets only the values that differ (each set sends a team packet), so a team saved by
	 * an earlier run is corrected too.
	 *
	 * @return whether anything was changed
	 */
	static boolean makeCosmetic(final PlayerTeam team) {
		boolean changed = false;
		if (team.canSeeFriendlyInvisibles()) {
			team.setSeeFriendlyInvisibles(false);
			changed = true;
		}
		if (!team.isAllowFriendlyFire()) {
			team.setAllowFriendlyFire(true);
			changed = true;
		}
		if (team.getNameTagVisibility() != Team.Visibility.ALWAYS) {
			team.setNameTagVisibility(Team.Visibility.ALWAYS);
			changed = true;
		}
		if (team.getCollisionRule() != Team.CollisionRule.ALWAYS) {
			team.setCollisionRule(Team.CollisionRule.ALWAYS);
			changed = true;
		}
		return changed;
	}
}
