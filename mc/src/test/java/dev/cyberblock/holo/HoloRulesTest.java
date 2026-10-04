package dev.cyberblock.holo;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.holo.HoloRules.Ask;
import dev.cyberblock.holo.HoloRules.Kind;
import dev.cyberblock.holo.HoloRules.Role;
import dev.cyberblock.holo.HoloRules.Who;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 7-T1: who sees whose pin on the Holo-Table, and the spawn rules. */
class HoloRulesTest {
	private static Who free(final boolean hidden) {
		return new Who(UUID.randomUUID(), null, -1, Role.PLAYER, hidden);
	}

	private static Who in(final String match, final int team) {
		return new Who(UUID.randomUUID(), match, team, Role.PLAYER, false);
	}

	private static Who as(final Role role, final String match) {
		return new Who(UUID.randomUUID(), match, -1, role, false);
	}

	@Test
	void outsideMatchesEveryPinShowsUnlessHidden() {
		Who viewer = free(false);
		assertTrue(HoloRules.pinVisible(viewer, free(false), false));
		assertFalse(HoloRules.pinVisible(viewer, free(true), false), "hide my pin");
		assertFalse(HoloRules.pinVisible(viewer, free(true), true), "a scan outside matches does not undo hiding");
	}

	@Test
	void yourOwnPinAlwaysShows() {
		Who me = free(true);
		assertTrue(HoloRules.pinVisible(me, me, false));
		Who player = in("m1", 2);
		assertTrue(HoloRules.pinVisible(player, player, false));
	}

	@Test
	void playersOutsideAMatchDoNotSeeThePlayersInIt() {
		assertFalse(HoloRules.pinVisible(free(false), in("m1", 0), false));
		assertFalse(HoloRules.pinVisible(free(false), in("m1", 0), true));
	}

	@Test
	void inMatchesOnlyYourTeamAndScanRevealedPins() {
		Who viewer = in("m1", 1);
		assertTrue(HoloRules.pinVisible(viewer, in("m1", 1), false), "teammate");
		assertTrue(HoloRules.pinVisible(viewer, new Who(UUID.randomUUID(), "m1", 1, Role.PLAYER, true), false), "a hidden teammate still shows");
		assertFalse(HoloRules.pinVisible(viewer, in("m1", 2), false), "the other team");
		assertTrue(HoloRules.pinVisible(viewer, in("m1", 2), true), "scan-revealed");
		assertFalse(HoloRules.pinVisible(viewer, in("m2", 1), false), "team 1 of another match is not your team");
		assertFalse(HoloRules.pinVisible(viewer, free(false), false), "players outside the match");
		assertFalse(HoloRules.pinVisible(in("m1", -1), in("m1", -1), false), "no team is nobody's team");
	}

	@Test
	void spectatorsAndOverlordsSeeEveryPin() {
		for (Role role : new Role[] {Role.SPECTATOR, Role.OVERLORD}) {
			Who viewer = as(role, "m1");
			assertTrue(HoloRules.pinVisible(viewer, in("m1", 1), false));
			assertTrue(HoloRules.pinVisible(viewer, in("m1", 2), false));
			assertTrue(HoloRules.pinVisible(viewer, free(true), false), role + " sees hidden pins too");
		}
	}

	@Test
	void teamSpawnsBelongToTheViewersTeamInItsMatch() {
		Who viewer = in("m1", 3);
		assertTrue(HoloRules.teamSpawn(viewer, "m1", 3));
		assertFalse(HoloRules.teamSpawn(viewer, "m1", 4));
		assertFalse(HoloRules.teamSpawn(viewer, "m2", 3));
		assertFalse(HoloRules.teamSpawn(viewer, null, 3));
		assertFalse(HoloRules.teamSpawn(free(false), null, -1), "outside matches nothing is a team spawn");
	}

	@Test
	void sixtySecondCooldownAndFiveSecondProtection() {
		assertEquals(0, HoloRules.cooldownLeft(null, 5_000_000L));
		assertEquals(60, HoloRules.cooldownLeft(5_000_000L, 5_000_000L));
		assertEquals(1, HoloRules.cooldownLeft(5_000_000L, 5_059_500L));
		assertEquals(0, HoloRules.cooldownLeft(5_000_000L, 5_060_000L));
		assertFalse(HoloRules.protectedNow(null, 1L));
		assertTrue(HoloRules.protectedNow(10_000L, 10_000L));
		assertTrue(HoloRules.protectedNow(10_000L, 14_999L));
		assertFalse(HoloRules.protectedNow(10_000L, 15_000L));
	}

	private static Ask landmark(final boolean lockedZone) {
		return new Ask(Kind.LANDMARK, false, lockedZone, false, false, false, false, false);
	}

	@Test
	void aFreeSpawnGoes() {
		assertNull(HoloRules.refusal(free(false), landmark(false), 0, false));
		assertNull(HoloRules.refusal(free(false), new Ask(Kind.BEACON, false, false, false, false, false, false, false), 0, false));
	}

	@Test
	void busyAndCooldownComeFirst() {
		assertEquals("cyberblock.holo.pending", HoloRules.refusal(free(false), landmark(true), 30, true));
		assertEquals("cyberblock.holo.cooldown", HoloRules.refusal(free(false), landmark(true), 30, false));
	}

	@Test
	void neverIntoALockedMatchZone() {
		assertEquals("cyberblock.holo.locked_zone", HoloRules.refusal(free(false), landmark(true), 0, false));
		// a team's own base lies inside its own match zone: allowed
		Ask base = new Ask(Kind.BASE, true, true, false, false, false, false, false);
		assertNull(HoloRules.refusal(in("m1", 1), base, 0, false));
	}

	@Test
	void inMatchesOnlyTheTeamsSpawns() {
		Who player = in("m1", 1);
		assertEquals("cyberblock.holo.team_only", HoloRules.refusal(player, landmark(false), 0, false));
		assertEquals("cyberblock.holo.team_only", HoloRules.refusal(player, new Ask(Kind.FRIEND, false, false, false, true, true, false, false), 0, false));
		assertNull(HoloRules.refusal(player, new Ask(Kind.BEACON, true, false, false, false, false, false, false), 0, false), "the team's beacon");
		// spectators may go anywhere (vanilla spectators cannot touch anything), overlords follow the player rules
		assertNull(HoloRules.refusal(as(Role.SPECTATOR, "m1"), landmark(true), 0, false));
		assertEquals("cyberblock.holo.team_only", HoloRules.refusal(as(Role.OVERLORD, "m1"), landmark(false), 0, false));
	}

	@Test
	void joinAFriend() {
		Who viewer = free(false);
		assertEquals("cyberblock.holo.self", HoloRules.refusal(viewer, new Ask(Kind.FRIEND, false, false, true, true, true, false, false), 0, false));
		assertEquals("cyberblock.holo.friend_hidden", HoloRules.refusal(viewer, new Ask(Kind.FRIEND, false, false, false, false, true, false, false), 0, false));
		assertEquals("cyberblock.holo.friend_away", HoloRules.refusal(viewer, new Ask(Kind.FRIEND, false, false, false, true, false, false, false), 0, false));
		assertNull(HoloRules.refusal(viewer, new Ask(Kind.FRIEND, false, false, false, true, true, false, false), 0, false));
	}

	@Test
	void aLinkedPlayersVMustBeFree() {
		assertEquals("cyberblock.holo.held", HoloRules.refusal(free(false), new Ask(Kind.LANDMARK, false, false, false, false, false, true, true), 0, false));
		assertEquals("cyberblock.holo.vehicle", HoloRules.refusal(free(false), new Ask(Kind.LANDMARK, false, false, false, false, false, false, true), 0, false));
	}

	@Test
	void steveFollowsVsSnappedSpotWithin32Blocks() {
		double[] asked = {100, 64, 100};
		assertArrayEquals(new double[] {110, 70, 90}, HoloRules.landing(new double[] {110, 70, 90}, asked));
		assertArrayEquals(asked, HoloRules.landing(new double[] {200, 70, 100}, asked), "too far: the asked spot");
		assertArrayEquals(asked, HoloRules.landing(null, asked));
	}

	@Test
	void fastTravelFollowsTheSpawnRules() {
		Who host = free(false);
		assertNull(HoloRules.travelRefusal(host, true, false, false, false, 0, false));
		assertEquals("cyberblock.holo.pending", HoloRules.travelRefusal(host, true, false, false, false, 0, true));
		assertEquals("cyberblock.holo.cooldown", HoloRules.travelRefusal(host, true, false, false, false, 12, false));
		assertEquals("cyberblock.holo.travel_unlinked", HoloRules.travelRefusal(host, false, false, false, false, 0, false));
		assertEquals("cyberblock.holo.team_only", HoloRules.travelRefusal(in("m1", 1), true, false, false, false, 0, false), "never a team spawn");
		assertEquals("cyberblock.holo.locked_zone", HoloRules.travelRefusal(host, true, true, false, false, 0, false));
		assertEquals("cyberblock.holo.held", HoloRules.travelRefusal(host, true, false, true, false, 0, false));
		assertEquals("cyberblock.holo.vehicle", HoloRules.travelRefusal(host, true, false, false, true, 0, false));
	}

	@Test
	void beaconsFollowTheirOwnersPrivacy() {
		Who viewer = free(false);
		UUID owner = UUID.randomUUID();
		assertTrue(HoloRules.beaconOffered(viewer, owner, false, null, -1));
		assertFalse(HoloRules.beaconOffered(viewer, owner, true, null, -1), "the owner hid its pin");
		assertFalse(HoloRules.beaconOffered(viewer, owner, false, "m1", 0), "placed in a match");
		assertTrue(HoloRules.beaconOffered(viewer, viewer.uuid(), true, "m1", 0), "your own beacon always");
		Who player = in("m1", 2);
		assertTrue(HoloRules.beaconOffered(player, owner, true, "m1", 2), "a hidden teammate's beacon is a team spawn");
		assertFalse(HoloRules.beaconOffered(player, owner, false, "m1", 1), "the other team's");
		assertFalse(HoloRules.beaconOffered(player, owner, false, null, -1), "outside the match");
		assertFalse(HoloRules.beaconOffered(player, player.uuid(), false, null, -1), "in a match only team spawns, even your own");
	}

	@Test
	void requestsAreRateLimited() {
		double[] bucket = HoloRules.bucket(1_000L);
		for (int i = 0; i < 4; i++) {
			assertTrue(HoloRules.take(bucket, 1_000L), "a burst of 4");
		}

		assertFalse(HoloRules.take(bucket, 1_000L));
		assertFalse(HoloRules.take(bucket, 1_400L), "0.8 of a request after 400 ms");
		assertTrue(HoloRules.take(bucket, 1_500L), "2 a second");
		assertFalse(HoloRules.take(bucket, 1_500L));
		for (int i = 0; i < 4; i++) {
			assertTrue(HoloRules.take(bucket, 100_000L));
		}

		assertFalse(HoloRules.take(bucket, 100_000L), "the burst refills to 4, never more");
	}

	@Test
	void kindKeys() {
		for (Kind k : Kind.values()) {
			assertEquals(k, Kind.of(k.key()));
		}

		assertNull(Kind.of("nowhere"));
		assertEquals("overlord", Role.OVERLORD.key());
	}
}
