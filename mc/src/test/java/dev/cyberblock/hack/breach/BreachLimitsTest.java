package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.device.DeviceRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** protocol.md "Phase 6": 10 s for the same player and device after a failure; one alarm per owner a minute. */
class BreachLimitsTest {
	private static final UUID HOST = new UUID(1, 1);
	private static final UUID GUEST = new UUID(2, 2);
	private static final DeviceRegistry.Key CAMERA = new DeviceRegistry.Key(HOST, 10);
	private static final DeviceRegistry.Key TERMINAL = new DeviceRegistry.Key(HOST, 11);
	private static final BreachLimits.Hold GUEST_CAMERA = new BreachLimits.Hold(GUEST, CAMERA);

	@Test
	void aFailureCoolsDownThatPlayerOnThatDeviceForTenSeconds() {
		BreachLimits l = new BreachLimits();
		assertEquals(0, l.cooldown(GUEST_CAMERA, 100));
		l.failed(GUEST_CAMERA, 100);
		assertEquals(200, BreachLimits.COOLDOWN_TICKS, "10 s");
		assertEquals(200, l.cooldown(GUEST_CAMERA, 100));
		assertEquals(1, l.cooldown(GUEST_CAMERA, 299));
		assertEquals(0, l.cooldown(GUEST_CAMERA, 300));
		assertEquals(0, l.cooldown(new BreachLimits.Hold(GUEST, TERMINAL), 100), "another device is free");
		assertEquals(0, l.cooldown(new BreachLimits.Hold(new UUID(3, 3), CAMERA), 100), "another player is free");
	}

	@Test
	void anOwnerGetsAtMostOneAlarmAMinute() {
		BreachLimits l = new BreachLimits();
		assertTrue(l.alarm(HOST, 100));
		assertFalse(l.alarm(HOST, 101), "a second failure right after is merged");
		assertFalse(l.alarm(HOST, 100 + BreachLimits.ALARM_TICKS - 1));
		assertTrue(l.alarm(new UUID(3, 3), 101), "another owner has its own window");
		assertTrue(l.alarm(HOST, 100 + BreachLimits.ALARM_TICKS), "a minute later the next failure raises an alarm again");
		assertFalse(l.alarm(HOST, 100 + BreachLimits.ALARM_TICKS + 1), "and opens a new window");
	}

	@Test
	void aGuestFailingBreachAfterBreachRaisesOneAlarmAMinute() {
		BreachLimits l = new BreachLimits();
		int alarms = 0;
		long t = 0;
		// a guest fails one device after another as fast as the cooldown allows: every alarm after the first within a minute merges
		for (int i = 0; i < 24; i++) {
			BreachLimits.Hold hold = new BreachLimits.Hold(GUEST, new DeviceRegistry.Key(HOST, 100 + i % 2));
			t += l.cooldown(hold, t);
			l.failed(hold, t);
			alarms += l.alarm(HOST, t) ? 1 : 0;
			t += 100;
		}

		assertEquals(2, alarms, "twenty-four failures in two minutes: one alarm a minute");
	}

	@Test
	void theTestBypassClearsTheCooldownButNotTheAlarmWindow() {
		BreachLimits l = new BreachLimits();
		l.failed(GUEST_CAMERA, 100);
		assertTrue(l.alarm(HOST, 100));
		l.clear(GUEST_CAMERA);
		assertEquals(0, l.cooldown(GUEST_CAMERA, 101));
		assertFalse(l.alarm(HOST, 101));
	}

	@Test
	void pruneForgetsWhatRanOut() {
		BreachLimits l = new BreachLimits();
		l.failed(GUEST_CAMERA, 0);
		assertTrue(l.alarm(HOST, 0));
		l.prune(BreachLimits.ALARM_TICKS);
		assertEquals(0, l.cooldown(GUEST_CAMERA, 0), "forgotten, so free even at an earlier tick");
		assertTrue(l.alarm(HOST, BreachLimits.ALARM_TICKS));
	}
}
