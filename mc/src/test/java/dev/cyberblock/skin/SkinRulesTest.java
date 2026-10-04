package dev.cyberblock.skin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.junit.jupiter.api.Test;

/** Megaplan 5-K1: body names and the [V] team plan. */
class SkinRulesTest {
	@Test
	void bodiesAreMaleOrFemaleOnly() {
		assertEquals("male", SkinRules.parse("male"));
		assertEquals("female", SkinRules.parse(" Female "));
		assertNull(SkinRules.parse("robot"));
		assertNull(SkinRules.parse(""));
		assertNull(SkinRules.parse(null));
	}

	@Test
	void linkedPlayersJoinAndTheRestLeave() {
		SkinRules.TeamPlan plan = SkinRules.teamPlan(Set.of("CbHost1", "CbHost2"), Set.of("CbHost2", "CbGuest1", "Gone"), name -> false);
		assertEquals(List.of("CbGuest1", "Gone"), plan.leave(), "unlinked and offline names leave, in order");
		assertEquals(List.of("CbHost1"), plan.join(), "a member already in stays without a change");
		assertFalse(plan.empty());
	}

	@Test
	void aPlayerInAnotherTeamIsLeftThere() {
		SkinRules.TeamPlan plan = SkinRules.teamPlan(Set.of("CbHost1", "CbHost2"), Set.of(), "CbHost2"::equals);
		assertEquals(List.of("CbHost1"), plan.join());
		assertTrue(plan.leave().isEmpty());
	}

	@Test
	void theVTeamIsCosmeticNotAnAlliance() {
		PlayerTeam team = new Scoreboard().addPlayerTeam("cyberblock_v");
		assertTrue(team.canSeeFriendlyInvisibles(), "vanilla default: team mates see each other's invisibility");
		team.setAllowFriendlyFire(false);
		team.setNameTagVisibility(Team.Visibility.NEVER);
		team.setCollisionRule(Team.CollisionRule.NEVER);

		assertTrue(SkinRules.makeCosmetic(team), "a saved team from an earlier run is corrected");
		assertFalse(team.canSeeFriendlyInvisibles(), "linked hosts must not see each other's Optical Camo");
		assertTrue(team.isAllowFriendlyFire());
		assertEquals(Team.Visibility.ALWAYS, team.getNameTagVisibility());
		assertEquals(Team.CollisionRule.ALWAYS, team.getCollisionRule());
		assertFalse(SkinRules.makeCosmetic(team), "no change (no team packet) once it is cosmetic");
	}

	@Test
	void nothingToDoWhenTheTeamMatches() {
		assertTrue(SkinRules.teamPlan(Set.of("CbHost1"), Set.of("CbHost1"), name -> false).empty());
		assertTrue(SkinRules.teamPlan(Set.of(), Set.of(), name -> false).empty());
	}
}
