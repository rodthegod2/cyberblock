package dev.cyberblock.hack.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The server rules of the interaction map that need no running server (megaplan task 6-I1). */
class RemoteActionsTest {
	private static final UUID OWNER = new UUID(1, 1), ANA = new UUID(2, 1);

	private static String key(final Component c) {
		return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
	}

	@Test
	void nightCitysRefusalsReadAsText() {
		// 6S answers the out-of-range rule with the lang key, and a target that left the stream with "target gone"
		assertEquals("cyberblock.hack.out_of_range", key(RemoteActions.reasonText("cyberblock.hack.out_of_range")));
		assertEquals("cyberblock.hack.out_of_range", key(RemoteActions.reasonText("target gone")));
		Component other = RemoteActions.reasonText("cyberblock.hack.denied");
		assertEquals("cyberblock.hack.denied", key(other));
		assertEquals("refused", ((TranslatableContents) other.getContents()).getFallback(), "never the raw key on screen");
		Component plain = RemoteActions.reasonText("Cyberblock is not active");
		assertTrue(plain.getContents() instanceof PlainTextContents);
		assertEquals("Cyberblock is not active", plain.getString());
	}

	@Test
	void aBlackoutThatDidNotHappenFreesTheOwnersLock() {
		Cooldowns c = new Cooldowns();
		String lock = RemoteActions.blackoutKey(OWNER);
		c.start(lock, 0L, 300L);
		RemoteActions.finished(c, OWNER, RemoteHack.BLACKOUT, true);
		assertEquals(300L, c.secondsLeft(lock, 0L), "a blackout Night City ran keeps its lock");
		RemoteActions.finished(c, OWNER, RemoteHack.GRIDLOCK, false);
		assertEquals(300L, c.secondsLeft(lock, 0L), "another hack's failure is not the blackout's");
		RemoteActions.finished(c, OWNER, RemoteHack.BLACKOUT, false);
		assertEquals(0L, c.secondsLeft(lock, 0L), "refused or timed out: free at once");
	}

	@Test
	void aCameraViewNeedsTheCameraWithinTheViewDistance() {
		assertEquals(9 * 16, RemoteActions.cameraReach(12, 10), "the server's distance caps the client's");
		assertEquals(5 * 16, RemoteActions.cameraReach(6, 10));
		assertEquals(16, RemoteActions.cameraReach(0, 10), "vanilla's floor of 2 chunks");
		int reach = RemoteActions.cameraReach(10, 10);
		assertTrue(RemoteActions.cameraInReach(Vec3.ZERO, new Vec3(reach, 200, 0), reach), "height does not count");
		assertFalse(RemoteActions.cameraInReach(Vec3.ZERO, new Vec3(reach, 0, 1), reach));
		assertFalse(RemoteActions.cameraInReach(new Vec3(5000, 64, 5000), new Vec3(0, 64, 0), reach), "a camera across the void world");
	}

	@Test
	void theFeedNeedsALiveHostAndItsHacksToggle() {
		assertEquals("cyberblock.hack.no_link", key(RemoteActions.feedRefusal(false, true)), "an unknown or inactive host");
		assertEquals("cyberblock.guest.blocked", key(RemoteActions.feedRefusal(true, false)), "guests hacks off");
		assertNull(RemoteActions.feedRefusal(true, true));
	}

	@Test
	void oneFeedMapEveryFiveSecondsAndNoneForAnUnknownHost() {
		Cooldowns c = new Cooldowns();
		Component unknown = RemoteActions.feedRefusal(false, false);
		assertSame(unknown, RemoteActions.feedMapRefusal(c, ANA, 0L, unknown));
		assertNull(RemoteActions.feedMapRefusal(c, ANA, 0L, null), "a refusal starts no wait");
		for (long t = 0L; t < RemoteActions.FEEDMAP_SECONDS * 1000L; t += 250L) {
			Component why = RemoteActions.feedMapRefusal(c, ANA, t, null);
			assertNotNull(why, "repeated at " + t + " ms");
			assertEquals("cyberblock.feed.map_wait", key(why));
		}

		assertNull(RemoteActions.feedMapRefusal(c, ANA, RemoteActions.FEEDMAP_SECONDS * 1000L, null));
	}
}
