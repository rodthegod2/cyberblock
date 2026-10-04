package dev.cyberblock.mode.downed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 8-T2 "Done: tests": the bleed-out, the 4 s revive hold, Trauma Team, hits while down, who goes down, who may revive. */
class DownedRulesTest {
	private static final UUID MATE = new UUID(0, 1), OTHER = new UUID(0, 2);

	private static DownState.Outcome run(final DownState s, final long ticks) {
		DownState.Outcome last = DownState.Outcome.NONE;
		for (long i = 0; i < ticks && last == DownState.Outcome.NONE; i++) {
			last = s.tick();
		}

		return last;
	}

	@Test
	void bleedsOutAfter60Seconds() {
		DownState s = new DownState();
		assertEquals(DownState.Outcome.NONE, run(s, 20 * 60 - 1));
		assertEquals(1, s.bleedLeft());
		assertEquals(DownState.Outcome.BLED_OUT, s.tick());
	}

	@Test
	void aTeammateHoldingRightClickFor4SecondsRevives() {
		DownState s = new DownState();
		long t = 100;
		assertFalse(s.hold(MATE, t));
		// the client repeats the use packet every 4 ticks while the button is held
		for (; t < 100 + DownState.REVIVE_TICKS - 4; t += 4) {
			assertFalse(s.hold(MATE, t), "not yet at " + (t - 100));
		}

		assertEquals(0.95F, s.revive(t - 4), 0.06F);
		assertEquals(MATE, s.reviver(t - 4));
		assertTrue(s.hold(MATE, 100 + DownState.REVIVE_TICKS));
	}

	@Test
	void lettingGoStartsTheReviveOver() {
		DownState s = new DownState();
		s.hold(MATE, 0);
		s.hold(MATE, 40);
		assertFalse(s.hold(MATE, 40 + DownState.HOLD_GAP_TICKS + 1), "a gap: let go");
		assertEquals(0.0F, s.revive(40 + DownState.HOLD_GAP_TICKS + 1));
		assertFalse(s.hold(MATE, 40 + DownState.HOLD_GAP_TICKS + 1 + 79));
		assertEquals(0.0F, s.revive(500), "nobody holds any more");
		assertNull(s.reviver(500));
	}

	@Test
	void aSecondReviverStartsOver() {
		DownState s = new DownState();
		for (long t = 0; t < 60; t += 4) {
			s.hold(MATE, t);
		}

		assertFalse(s.hold(OTHER, 64));
		assertFalse(s.hold(OTHER, 64 + 40), "the other one has held for only 2 s");
	}

	@Test
	void traumaTeamLands30SecondsAfterTheCallAndTheBleedOutWaits() {
		DownState s = new DownState();
		run(s, 20 * 50); // 10 s of bleed-out left
		assertTrue(s.callTrauma());
		assertFalse(s.callTrauma(), "already on its way");
		assertEquals(DownState.TRAUMA_TICKS, s.traumaLeft());
		assertEquals(DownState.Outcome.NONE, run(s, DownState.TRAUMA_TICKS - 1), "no bleed-out while the flyer flies");
		assertEquals(DownState.Outcome.TRAUMA_ARRIVED, s.tick());
	}

	@Test
	void hitsWhileDownCostBleedOutTimeAtMostOnceASecond() {
		DownState s = new DownState();
		assertTrue(s.hit(100));
		assertEquals(DownState.BLEED_TICKS - DownState.HIT_PENALTY_TICKS, s.bleedLeft());
		assertFalse(s.hit(110), "a burst counts once");
		assertTrue(s.hit(120));
		for (long t = 140; t < 20 * 60 * 5; t += 20) {
			s.hit(t);
		}

		assertEquals(1, s.bleedLeft(), "never past the last tick: the bleed-out still ends the normal way");
		assertEquals(DownState.Outcome.BLED_OUT, s.tick());
		DownState flying = new DownState();
		flying.callTrauma();
		assertFalse(flying.hit(100), "the flyer is coming: hits do not count");
	}

	@Test
	void givingUpBleedsOutNow() {
		DownState s = new DownState();
		s.callTrauma();
		s.giveUp();
		assertFalse(s.traumaCalled());
		assertEquals(DownState.Outcome.BLED_OUT, s.tick());
	}

	@Test
	void whoGoesDownInsteadOfDying() {
		assertEquals(DownedRules.Death.DOWN, DownedRules.onDeath(false, false, false, false, false, false));
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(true, false, false, false, false, false), "the bleed-out ending");
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(false, true, false, false, false, false), "already down");
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(false, false, true, false, false, false), "the void, /kill");
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(false, false, false, true, false, false), "a totem: vanilla saves");
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(false, false, false, false, true, false), "a ready Second Heart: the implant saves");
		assertEquals(DownedRules.Death.DIE, DownedRules.onDeath(false, false, false, false, false, true), "creative, spectator");
	}

	@Test
	void whoMayRevive() {
		assertFalse(DownedRules.mayRevive(true, null, -1, null, -1, false, false), "never oneself");
		assertTrue(DownedRules.mayRevive(false, null, -1, null, -1, false, false), "no teams: anyone");
		assertTrue(DownedRules.mayRevive(false, null, -1, null, -1, true, true), "vanilla allies");
		assertFalse(DownedRules.mayRevive(false, null, -1, null, -1, true, false), "another vanilla team");
		assertTrue(DownedRules.mayRevive(false, "m1", 2, "m1", 2, false, false), "the same match team");
		assertFalse(DownedRules.mayRevive(false, "m1", 2, "m1", 1, false, false), "the other match team");
		assertFalse(DownedRules.mayRevive(false, "m1", 2, null, -1, false, true), "outside the match");
		assertTrue(DownedRules.mayRevive(false, "m1", -1, "m1", 0, false, false), "a match without teams: its players");
	}

	@Test
	void secondsRoundUp() {
		assertEquals(60, DownedRules.seconds(DownState.BLEED_TICKS));
		assertEquals(1, DownedRules.seconds(1));
		assertEquals(0, DownedRules.seconds(0));
	}

	@Test
	void wireRoundTrips() {
		DownedWire.Status s = new DownedWire.Status(true, 42, 12, 0.5F, "CbGuest1", 250, false);
		assertEquals(s, DownedWire.parseStatus(DownedWire.status(s)));
		assertEquals(DownedWire.Status.UP, DownedWire.parseStatus(DownedWire.status(DownedWire.Status.UP)));
		assertNull(DownedWire.parseStatus("{\"down\":true,\"left\":-3,\"trauma\":-1,\"revive\":0,\"by\":\"\",\"price\":1}"));
		assertNull(DownedWire.parseStatus("{\"down\":true,\"left\":3,\"trauma\":-1,\"revive\":2,\"by\":\"\",\"price\":1}"));
		DownedWire.Revive r = new DownedWire.Revive("CbHost1", 0.25F);
		assertEquals(r, DownedWire.parseRevive(DownedWire.revive(r)));
		assertNull(DownedWire.parseRevive("{\"name\":\"x\",\"p\":1.5}"));
	}
}
