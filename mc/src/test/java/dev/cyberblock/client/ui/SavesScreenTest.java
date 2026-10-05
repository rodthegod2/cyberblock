package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.ui.screen.NightCityPauseScreen;
import dev.cyberblock.client.ui.screen.SavesScreen;
import dev.cyberblock.client.ui.screen.SavesScreen.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Megaplan 9-U1: the "Night City saves" screen sends the actions Saves.reds reads, and the escape hatch to Cyberpunk's own menu
 * works in every state of the screen (no link, no list yet, no list at all, an empty list, a list). Lives in the {@code ui}
 * package because it brings the link up through {@link UiLink}'s package-private registration.
 */
class SavesScreenTest {
	private static final SavesModel.Save SAVE = new SavesModel.Save(4, "ManualSave-9", "Japantown", "ManualSave", 20, 90000L, null, "", "Nomad", false);
	private static final SavesModel.Save DAMAGED = new SavesModel.Save(5, "ManualSave-8", "Watson", "ManualSave", 3, 60L, null, "", "Nomad", true);
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

	private JsonObject last() {
		return JsonParser.parseString(this.sent.getLast()).getAsJsonObject();
	}

	@Test
	void theListRequestIsAJournalRequestForSaves() {
		hostSpeaks();
		assertTrue(SavesScreen.requestSaves(s -> {
		}) > 0);
		JsonObject m = this.last();
		assertEquals("uiact", m.get("t").getAsString());
		assertEquals("journalreq", m.get("a").getAsString());
		assertEquals("saves", m.get("arg").getAsString());
	}

	@Test
	void theReplyReachesTheScreenAsSaves() {
		hostSpeaks();
		List<SavesModel.Saves> got = new ArrayList<>();
		int id = SavesScreen.requestSaves(got::add);
		JsonObject reply = JsonParser.parseString("""
			{"t":"reply","kind":"journal","id":%d,"groups":[{"hash":0,"title":"ok","kind":"other","tracked":false,"done":false,"lines":[]},
			{"hash":4,"title":"Japantown","kind":"other","tracked":false,"done":false,"lines":[{"hash":1,"text":"ManualSave-9","state":0}]}]}
			""".formatted(id)).getAsJsonObject();
		UiModel.Reply parsed = UiModel.parseReply(reply);
		assertNotNull(parsed);
		got.add(SavesModel.of(parsed));
		assertEquals(List.of("ManualSave-9"), got.getFirst().list().stream().map(SavesModel.Save::key).toList());
	}

	@Test
	void loadSendsTheNameAndIndex() {
		hostSpeaks();
		assertTrue(SavesScreen.load(SAVE));
		JsonObject m = this.last();
		assertEquals("loadlast", m.get("a").getAsString());
		assertEquals("ManualSave-9", m.get("arg").getAsString());
		assertEquals(4, m.get("id").getAsInt());
	}

	@Test
	void aDamagedSaveIsNeverSent() {
		hostSpeaks();
		assertFalse(SavesScreen.load(DAMAGED));
		assertTrue(this.sent.isEmpty());
	}

	@Test
	void aSaveWithoutMetadataIsNeverSent() {
		hostSpeaks();
		assertFalse(SavesScreen.load(new SavesModel.Save(6, "QuickSave-1", "Watson", "", -1, -1L, null, "", "", false)));
		assertTrue(this.sent.isEmpty());
	}

	@Test
	void theConfirmationTellsTwoSavesAtOnePlaceApart() {
		SavesModel.Save undated = new SavesModel.Save(7, "AutoSave-4", "Japantown", "", 20, 90000L, null, "", "Nomad", false);
		assertEquals("AutoSave-4", SavesScreen.describe(undated), "no date and no type: its name");
		SavesModel.Save dated = new SavesModel.Save(8, "ManualSave-10", "Japantown", "ManualSave", 20, 90000L, java.time.Instant.ofEpochSecond(1_759_000_000L),
			"", "Nomad", false);
		String text = SavesScreen.describe(dated);
		assertTrue(text.startsWith("Manual save, "), text);
		assertNotEquals(SavesScreen.describe(SAVE), text);
	}

	@Test
	void newSaveSendsSaveNew() {
		hostSpeaks();
		assertTrue(SavesScreen.newSave());
		assertEquals("save", this.last().get("a").getAsString());
		assertEquals("new", this.last().get("arg").getAsString());
	}

	@Test
	void withoutALinkNothingIsSent() {
		UiLink.forgetHost();
		assertEquals(-1, SavesScreen.requestSaves(s -> {
		}));
		assertFalse(SavesScreen.load(SAVE));
		assertFalse(SavesScreen.newSave());
		assertTrue(this.sent.isEmpty());
	}

	@Test
	void theEscapeHatchWorksInEveryStateOfTheScreen() {
		// every list state is a selection of null (no list, no list at all, empty list) or a save; none of them touches the hatch
		for (SavesModel.Save selected : new SavesModel.Save[] {null, SAVE, DAMAGED}) {
			assertTrue(SavesScreen.usable(Item.CP_MENU, true, selected));
			assertTrue(SavesScreen.usable(Item.BACK, false, selected), "Back needs no link");
			assertTrue(SavesScreen.usable(Item.LOAD_LAST, true, selected));
			assertTrue(SavesScreen.usable(Item.QUICK_SAVE, true, selected));
		}

		hostSpeaks();
		assertTrue(NightCityPauseScreen.openCyberpunkMenu());
		assertEquals("cpmenu", this.last().get("a").getAsString());
		assertEquals("pause", this.last().get("arg").getAsString());
	}

	@Test
	void everyButtonButBackNeedsTheLinkAndLoadNeedsALoadableSave() {
		for (Item item : Item.values()) {
			assertEquals(item == Item.BACK, SavesScreen.usable(item, false, SAVE), item.name());
		}

		assertTrue(SavesScreen.usable(Item.LOAD, true, SAVE));
		assertFalse(SavesScreen.usable(Item.LOAD, true, DAMAGED));
		assertFalse(SavesScreen.usable(Item.LOAD, true, null));
	}

	@Test
	void thePauseScreenKeepsItsHatchNextToTheSavesButton() {
		List<NightCityPauseScreen.Item> items = List.of(NightCityPauseScreen.Item.values());
		assertTrue(items.contains(NightCityPauseScreen.Item.SAVES));
		assertTrue(items.contains(NightCityPauseScreen.Item.CP_MENU));
		assertTrue(NightCityPauseScreen.usable(NightCityPauseScreen.Item.SAVES, true));
		assertFalse(NightCityPauseScreen.usable(NightCityPauseScreen.Item.SAVES, false));
	}
}
