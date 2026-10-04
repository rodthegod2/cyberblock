package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Megaplan Q-U3: the escape hatch to Cyberpunk's own menu always works, and a lost link never strands the player. Lives in the
 * {@code ui} package because it brings the link up through {@link UiLink}'s package-private registration.
 */
class PauseEscapeHatchTest {
	private final List<String> sent = new ArrayList<>();
	private Consumer<String> before;

	@BeforeEach
	void capture() {
		UiLink.register();
		this.before = HostLink.swapOut(this.sent::add);
	}

	@AfterEach
	void restore() {
		HostLink.swapOut(this.before);
		UiLink.forgetHost();
	}

	private static void hostSpeaks() {
		HostLink.dispatch("cam", new JsonObject());
	}

	@Test
	void nightCityMenuButtonSendsCpmenuWhileLinked() {
		hostSpeaks();
		assertTrue(UiLink.linked());
		assertTrue(NightCityPauseScreen.usable(Item.CP_MENU, true));
		assertTrue(NightCityPauseScreen.openCyberpunkMenu());
		assertEquals(1, this.sent.size());
		JsonObject m = JsonParser.parseString(this.sent.get(0)).getAsJsonObject();
		assertEquals("uiact", m.get("t").getAsString());
		assertEquals("cpmenu", m.get("a").getAsString());
		assertEquals("pause", m.get("arg").getAsString());
	}

	@Test
	void withoutALinkNothingIsSentAndTheButtonIsGrey() {
		UiLink.forgetHost();
		assertFalse(UiLink.linked());
		assertFalse(NightCityPauseScreen.openCyberpunkMenu());
		assertTrue(this.sent.isEmpty());
		assertFalse(NightCityPauseScreen.usable(Item.CP_MENU, false));
	}

	@Test
	void everyStateOfTheScreenLeavesAWayOut() {
		for (boolean linked : new boolean[] {true, false}) {
			// the button list is the enum: the escape hatch is in it in both states, and the exits that need no link stay usable
			assertTrue(List.of(Item.values()).contains(Item.CP_MENU));
			assertTrue(NightCityPauseScreen.usable(Item.BACK, linked), "back, linked " + linked);
			assertTrue(NightCityPauseScreen.usable(Item.OPTIONS, linked), "options, linked " + linked);
			assertTrue(NightCityPauseScreen.usable(Item.CYBERBLOCK, linked), "Cyberblock settings, linked " + linked);
			assertEquals(linked, NightCityPauseScreen.usable(Item.CP_MENU, linked));
		}
	}

	@Test
	void everyButtonThatTalksToCyberpunkNeedsTheLink() {
		for (Item item : Item.values()) {
			boolean noLink = switch (item) {
				case BACK, OPTIONS, CYBERBLOCK -> true;
				default -> false;
			};
			assertEquals(noLink, NightCityPauseScreen.usable(item, false), item.name());
			assertTrue(NightCityPauseScreen.usable(item, true), item.name());
		}
	}
}
