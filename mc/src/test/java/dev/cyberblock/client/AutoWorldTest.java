package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan retest3 M5: the title screen's "Play Cyberblock" button never covers a vanilla widget, the logo or the splash. */
class AutoWorldTest {
	/** Vanilla 26.3's title screen widgets (TitleScreen.init) for a GUI of this size, {x, y, w, h}. */
	private static List<int[]> vanilla(final int width, final int height) {
		List<int[]> widgets = new ArrayList<>();
		int y = height / 4 + 48;
		for (int row = 0; row < 3; row++) { // Singleplayer, Multiplayer, Realms
			widgets.add(new int[] {width / 2 - 100, y + row * 24, 200, 20});
		}

		y += 3 * 24;
		for (int i = 0; i < 3; i++) { // friends, language, accessibility
			widgets.add(new int[] {width / 2 - 34 + i * 24, y, 20, 20});
		}

		y += 24;
		widgets.add(new int[] {width / 2 - 100, y, 98, 20}); // Options...
		widgets.add(new int[] {width / 2 + 2, y, 98, 20}); // Quit Game
		widgets.add(new int[] {width - 200 - 2, height - 10, 200, 10}); // copyright
		return widgets;
	}

	private static void assertClear(final int[] at, final int width, final int height, final List<int[]> widgets) {
		assertTrue(at[0] >= 0 && at[1] >= 0 && at[0] + at[2] <= width && at[1] + AutoWorld.BUTTON_HEIGHT <= height, "on screen");
		for (int[] r : widgets) {
			boolean overlaps = at[0] < r[0] + r[2] && r[0] < at[0] + at[2] && at[1] < r[1] + r[3] && r[1] < at[1] + AutoWorld.BUTTON_HEIGHT;
			assertFalse(overlaps, "overlaps the widget at " + r[0] + "," + r[1]);
		}
	}

	@Test
	void aboveSingleplayerAt1080pWithTheAutoGuiScale() {
		// 1920x1080 at GUI scale 4
		List<int[]> widgets = vanilla(480, 270);
		int[] at = AutoWorld.place(480, 270, widgets);
		assertArrayEquals(new int[] {140, 91, 200}, at);
		assertClear(at, 480, 270, widgets);
		assertTrue(at[1] >= 30 + 52, "below the logo");
	}

	@Test
	void besideTheColumnWhenThereIsNoRoomAbove() {
		// a short window: the space above Singleplayer is the logo's
		List<int[]> widgets = vanilla(640, 240);
		int[] at = AutoWorld.place(640, 240, widgets);
		assertEquals(108, at[1], "on Singleplayer's row");
		assertEquals(424, at[0], "to its right");
		assertClear(at, 640, 240, widgets);
	}

	@Test
	void theCornerWhenNothingElseFits() {
		List<int[]> widgets = vanilla(320, 240);
		int[] at = AutoWorld.place(320, 240, widgets);
		assertArrayEquals(new int[] {4, 4, 100}, at);
		assertClear(at, 320, 240, widgets);
	}

	@Test
	void aTitleScreenWithoutWidgetsGetsTheCorner() {
		assertArrayEquals(new int[] {4, 4, 100}, AutoWorld.place(480, 270, List.of()));
	}
}
