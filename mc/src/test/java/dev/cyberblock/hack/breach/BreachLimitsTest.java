package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.device.DeviceRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreachLimitsTest {
	private static final UUID HOST = new UUID(1, 1);
	private static final UUID GUEST = new UUID(2, 2);
	private static final DeviceRegistry.Key CAMERA = new DeviceRegistry.Key(HOST, 10);
	private static final DeviceRegistry.Key TERMINAL = new DeviceRegistry.Key(HOST, 11);

	@Test
	void aBreachedDeviceWaitsThirtySecondsWhateverTheOutcome() {
		BreachLimits l = new BreachLimits();
		assertEquals(0, l.deviceWait(CAMERA, 100));
		l.ended(CAMERA, GUEST, false, 100);
		assertEquals(600, l.deviceWait(CAMERA, 100));
		assertEquals(1, l.deviceWait(CAMERA, 699));
		assertEquals(0, l.deviceWait(CAMERA, 700));
		assertEquals(0, l.deviceWait(TERMINAL, 100), "another device is free");
		assertEquals(0, l.playerWait(GUEST, 100), "a solved breach does not lock the player out");
	}

	@Test
	void aFailedBreachLocksThePlayerOutOfEveryDevice() {
		BreachLimits l = new BreachLimits();
		l.ended(CAMERA, GUEST, true, 100);
		assertEquals(BreachLimits.PLAYER_TICKS, l.playerWait(GUEST, 100));
		assertEquals(300, l.playerWait(GUEST, 400), "still locked out halfway, for the other terminal too");
		assertEquals(0, l.playerWait(GUEST, 700));
		assertEquals(0, l.playerWait(HOST, 100), "only the player who failed");
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
	void aGuestFailingBreachAfterBreachRaisesOneAlarm() {
		BreachLimits l = new BreachLimits();
		int alarms = 0;
		long t = 0;
		// a guest fails one device after another: each failure locks them out for 30 s, every alarm after the first within a minute merges
		for (int i = 0; i < 6; i++) {
			assertEquals(0, l.playerWait(GUEST, t));
			DeviceRegistry.Key device = new DeviceRegistry.Key(HOST, 100 + i);
			l.ended(device, GUEST, true, t);
			alarms += l.alarm(HOST, t) ? 1 : 0;
			t += l.playerWait(GUEST, t);
		}

		assertEquals(3, alarms, "six failures, 30 s apart: one alarm a minute");
	}

	@Test
	void theTestBypassClearsTheDeviceAndThePlayerButNotTheAlarmWindow() {
		BreachLimits l = new BreachLimits();
		l.ended(CAMERA, GUEST, true, 100);
		assertTrue(l.alarm(HOST, 100));
		l.clear(CAMERA, GUEST);
		assertEquals(0, l.deviceWait(CAMERA, 101));
		assertEquals(0, l.playerWait(GUEST, 101));
		assertFalse(l.alarm(HOST, 101));
	}

	@Test
	void pruneForgetsWhatRanOut() {
		BreachLimits l = new BreachLimits();
		l.ended(CAMERA, GUEST, true, 0);
		assertTrue(l.alarm(HOST, 0));
		l.prune(BreachLimits.ALARM_TICKS);
		assertEquals(0, l.deviceWait(CAMERA, 0), "forgotten, so free even at an earlier tick");
		assertEquals(0, l.playerWait(GUEST, 0));
		assertTrue(l.alarm(HOST, BreachLimits.ALARM_TICKS));
	}
}
