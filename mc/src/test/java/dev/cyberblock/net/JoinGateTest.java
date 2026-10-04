package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JoinGateTest {
	private static final String PASSWORD = "hunter2";
	private final UUID alice = new UUID(1, 1), bob = new UUID(2, 2);

	@Test
	void theCheckIsExactOnTheUtf8BytesAndTrimsWhatWasTyped() {
		assertTrue(JoinGate.matches("hunter2", PASSWORD));
		assertTrue(JoinGate.matches("  hunter2 ", PASSWORD), "the configured password is trimmed too");
		assertFalse(JoinGate.matches("hunter", PASSWORD), "a prefix");
		assertFalse(JoinGate.matches("hunter22", PASSWORD));
		assertFalse(JoinGate.matches("Hunter2", PASSWORD), "case counts");
		assertFalse(JoinGate.matches("", PASSWORD));
		assertTrue(JoinGate.matches("päss wörd", "päss wörd"));
		assertFalse(JoinGate.matches("pass word", "päss wörd"));
		assertFalse(JoinGate.matches("", ""), "no password never matches");
	}

	@Test
	void theRightPasswordUnlocks() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		assertTrue(g.locked(this.alice));
		assertFalse(g.locked(this.bob));
		assertEquals(JoinGate.Outcome.OK, g.attempt(this.alice, PASSWORD, PASSWORD, 10).outcome());
		assertFalse(g.locked(this.alice));
		assertEquals(JoinGate.Outcome.NOT_LOCKED, g.attempt(this.alice, "anything", PASSWORD, 5_000).outcome(), "once in, /login checks nothing");
	}

	@Test
	void fiveWrongPasswordsKickAndEachSaysHowManyAreLeft() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		for (int i = 1; i < JoinGate.MAX_ATTEMPTS; i++) {
			JoinGate.Result r = g.attempt(this.alice, "wrong" + i, PASSWORD, i * 1_000L);
			assertEquals(JoinGate.Outcome.WRONG, r.outcome());
			assertEquals(JoinGate.MAX_ATTEMPTS - i, r.attemptsLeft());
		}

		JoinGate.Result last = g.attempt(this.alice, "wrong5", PASSWORD, 5_000);
		assertEquals(JoinGate.Outcome.KICK, last.outcome());
		assertEquals(0, last.attemptsLeft());
		assertEquals(5, JoinGate.MAX_ATTEMPTS);
	}

	@Test
	void oneAttemptPerSecondAndATooFastOneIsNotCounted() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		assertEquals(JoinGate.Outcome.WRONG, g.attempt(this.alice, "a", PASSWORD, 100).outcome());
		JoinGate.Result fast = g.attempt(this.alice, PASSWORD, PASSWORD, 600);
		assertEquals(JoinGate.Outcome.TOO_FAST, fast.outcome(), "even the right password waits");
		assertEquals(4, fast.attemptsLeft());
		assertTrue(g.locked(this.alice));
		for (int i = 0; i < 20; i++) {
			assertEquals(JoinGate.Outcome.TOO_FAST, g.attempt(this.alice, "b", PASSWORD, 700 + i).outcome());
		}

		assertEquals(3, g.attempt(this.alice, "c", PASSWORD, 1_100).attemptsLeft(), "the flood did not use attempts");
		assertEquals(JoinGate.Outcome.OK, g.attempt(this.alice, PASSWORD, PASSWORD, 2_100).outcome());
		assertEquals(1_000, JoinGate.ATTEMPT_GAP_MS);
	}

	@Test
	void eachPlayerHasItsOwnAttempts() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		g.lock(this.bob, 0);
		g.attempt(this.alice, "x", PASSWORD, 0);
		assertEquals(JoinGate.Outcome.WRONG, g.attempt(this.bob, "x", PASSWORD, 10).outcome(), "alice's attempt does not slow bob down");
		assertEquals(3, g.attempt(this.bob, "y", PASSWORD, 1_010).attemptsLeft());
	}

	@Test
	void lockedPlayersAreOutOfTimeAfter120Seconds() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		g.lock(this.bob, 60_000);
		assertEquals(120, g.secondsLeft(this.alice, 0));
		assertEquals(61, g.secondsLeft(this.alice, 59_500));
		assertEquals(List.of(), g.timedOut(119_999));
		assertEquals(List.of(this.alice), g.timedOut(120_000));
		assertEquals(0, g.secondsLeft(this.alice, 200_000));
		g.lock(this.alice, 150_000);
		assertEquals(List.of(this.alice), g.timedOut(150_000), "locking again keeps the clock");
		g.unlock(this.alice);
		assertEquals(List.of(this.bob), g.timedOut(180_000));
		assertEquals(0, g.secondsLeft(this.alice, 0), "not locked");
		assertEquals(1, g.lockedCount());
	}

	@Test
	void wrongAttemptsSurviveAReconnectWithinTenMinutes() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		g.attempt(this.alice, "a", PASSWORD, 1_000);
		g.attempt(this.alice, "b", PASSWORD, 2_000);
		g.attempt(this.alice, "c", PASSWORD, 3_000);
		g.unlock(this.alice); // she left
		assertEquals(3, g.wrong(this.alice, 60_000));
		g.lock(this.alice, 60_000); // and joined again a minute later
		JoinGate.Result r = g.attempt(this.alice, "d", PASSWORD, 61_000);
		assertEquals(JoinGate.Outcome.WRONG, r.outcome());
		assertEquals(1, r.attemptsLeft(), "the reconnect did not give back the three used tries");
		assertEquals(JoinGate.Outcome.KICK, g.attempt(this.alice, "e", PASSWORD, 62_000).outcome());
		g.unlock(this.alice);
		assertTrue(g.exhausted(this.alice, 62_000 + JoinGate.STRIKE_MEMORY_MS - 1), "refused at once at the next join");
		assertEquals(1, g.minutesUntilRetry(this.alice, 62_000 + JoinGate.STRIKE_MEMORY_MS - 1));
		assertEquals(10, g.minutesUntilRetry(this.alice, 62_000));
		g.lock(this.alice, 70_000);
		assertEquals(JoinGate.Outcome.KICK, g.attempt(this.alice, PASSWORD, PASSWORD, 71_000).outcome(), "no try left: even the right password is not checked");
	}

	@Test
	void wrongAttemptsAreForgottenTenMinutesAfterTheLastOne() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		for (int i = 1; i <= JoinGate.MAX_ATTEMPTS; i++) {
			g.attempt(this.alice, "x" + i, PASSWORD, i * 1_000L);
		}

		g.unlock(this.alice);
		long last = JoinGate.MAX_ATTEMPTS * 1_000L;
		assertTrue(g.exhausted(this.alice, last + JoinGate.STRIKE_MEMORY_MS - 1));
		assertFalse(g.exhausted(this.alice, last + JoinGate.STRIKE_MEMORY_MS));
		assertEquals(0, g.wrong(this.alice, last + JoinGate.STRIKE_MEMORY_MS));
		g.lock(this.alice, last + JoinGate.STRIKE_MEMORY_MS);
		assertEquals(4, g.attempt(this.alice, "y", PASSWORD, last + JoinGate.STRIKE_MEMORY_MS + 1).attemptsLeft(), "five fresh tries");
		assertEquals(600_000, JoinGate.STRIKE_MEMORY_MS);
	}

	@Test
	void eachAttemptRestartsTheTenMinutesAndTimedOutSweepsForgottenOnes() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		g.attempt(this.alice, "a", PASSWORD, 0);
		g.attempt(this.alice, "b", PASSWORD, 500_000);
		assertEquals(2, g.wrong(this.alice, 900_000), "counted from the last attempt, not the first");
		g.unlock(this.alice);
		g.timedOut(500_000 + JoinGate.STRIKE_MEMORY_MS);
		assertEquals(0, g.wrong(this.alice, 0), "swept, so even an earlier clock sees nothing");
	}

	@Test
	void anotherUuidKeepsItsOwnTriesAndTheRightPasswordClearsThem() {
		JoinGate g = new JoinGate();
		g.lock(this.alice, 0);
		for (int i = 1; i <= JoinGate.MAX_ATTEMPTS; i++) {
			g.attempt(this.alice, "x" + i, PASSWORD, i * 1_000L);
		}

		g.unlock(this.alice);
		assertFalse(g.exhausted(this.bob, 10_000));
		g.lock(this.bob, 10_000);
		g.attempt(this.bob, "w", PASSWORD, 10_000);
		assertEquals(JoinGate.Outcome.OK, g.attempt(this.bob, PASSWORD, PASSWORD, 11_000).outcome());
		assertEquals(0, g.wrong(this.bob, 11_000), "a login clears the count");
		assertTrue(g.exhausted(this.alice, 11_000));
		g.clear();
		assertFalse(g.exhausted(this.alice, 11_000), "server stop forgets everything");
	}

	@Test
	void onlyLoginRunsWhileLocked() {
		for (String ok : new String[] {"login hunter2", "/login hunter2", "LOGIN x", "login", "cyberblock login hunter2", " /cyberblock   login x"}) {
			assertTrue(JoinGate.isLoginCommand(ok), ok);
		}

		for (String no : new String[] {"msg bob hi", "me waves", "loginx", "cyberblock status", "cyberblock", "tell bob login", "execute run login x", ""}) {
			assertFalse(JoinGate.isLoginCommand(no), no);
		}
	}
}
