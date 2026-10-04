package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Plan 3-E2/3-E3: the server's Meet Up request and Offline Builds summary lines become toasts; other lines do not. */
class Phase3ePromptsTest {
	@Test
	void aMeetUpRequestNamesWhoAsks() {
		Component line = Component.translatableWithFallback("cyberblock.meetup.request", "%s wants to meet up. %s", "CbHost1",
			Component.literal("[Accept]"));
		assertEquals("CbHost1", MeetUpPrompt.from(line));
		assertNull(MeetUpPrompt.from(Component.literal("CbHost1 wants to meet up.")));
		assertNull(MeetUpPrompt.from(Component.translatableWithFallback("cyberblock.meetup.sent", "sent to %s", "CbGuest1")));
	}

	@Test
	void theSummaryBecomesTheToastLine() {
		Component line = Component.translatableWithFallback("cyberblock.offline.summary", "While you were away: %s blocks placed, %s removed (by %s)", 12, 3,
			"CbGuest1, CbGuest2");
		assertEquals("12 blocks placed, 3 removed (by CbGuest1, CbGuest2)", OfflineBuildsToast.text(line));
		assertNull(OfflineBuildsToast.text(Component.translatableWithFallback("cyberblock.offline.summary", "broken %s", 1)));
		assertNull(OfflineBuildsToast.text(Component.literal("While you were away")));
	}
}
