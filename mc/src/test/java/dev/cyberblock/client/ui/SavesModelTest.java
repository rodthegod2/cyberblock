package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 9-U1: the save list Saves.reds sends, read back as saves (the reply shape of the Saves.reds header). */
class SavesModelTest {
	private static UiModel.Group status(final String title) {
		return new UiModel.Group(0L, title, "other", false, !title.equals("ok"), List.of());
	}

	private static UiModel.Group save(final int index, final String title, final boolean damaged, final UiModel.Line... lines) {
		return new UiModel.Group(index, title, "other", false, damaged, List.of(lines));
	}

	private static UiModel.Line line(final int hash, final String text) {
		return new UiModel.Line(hash, text, 0);
	}

	private static UiModel.Reply reply(final UiModel.Group... groups) {
		return new UiModel.Reply("journal", 7, List.of(groups), List.of());
	}

	@Test
	void readsEverySaveWithItsMetadataNewestFirst() {
		SavesModel.Saves s = SavesModel.of(reply(status("ok"),
			save(0, "Watson", false, line(1, "AutoSave-3"), line(2, "AutoSave|12|3700|1700000000|StreetKid")),
			save(1, "Japantown", false, line(1, "ManualSave-9"), line(2, "ManualSave|20|90000|1750000000|Nomad"), line(3, "The Heist"))));
		assertTrue(s.available());
		assertEquals(2, s.list().size());
		SavesModel.Save newest = s.list().getFirst();
		assertEquals(1, newest.index());
		assertEquals("ManualSave-9", newest.key());
		assertEquals("Japantown", newest.title());
		assertEquals("ManualSave", newest.type());
		assertEquals(20, newest.level());
		assertEquals(90000L, newest.played());
		assertEquals(Instant.ofEpochSecond(1750000000L), newest.time());
		assertEquals("The Heist", newest.job());
		assertEquals("Nomad", newest.lifePath());
		assertTrue(newest.loadable());
		assertEquals("AutoSave-3", s.list().get(1).key());
	}

	@Test
	void aSaveWithoutMetadataKeepsItsNameAndPlace() {
		SavesModel.Saves s = SavesModel.of(reply(status("ok"), save(0, "", false, line(1, "QuickSave-1")),
			save(1, "Pacifica", false, line(1, "ManualSave-2"), line(2, "ManualSave|5|60|1700000000|Corporate"))));
		assertEquals(List.of("ManualSave-2", "QuickSave-1"), s.list().stream().map(SavesModel.Save::key).toList());
		SavesModel.Save bare = s.list().get(1);
		assertEquals("QuickSave-1", bare.title());
		assertEquals(-1, bare.level());
		assertEquals(-1L, bare.played());
		assertNull(bare.time());
		assertTrue(bare.loadable());
	}

	@Test
	void undatedSavesKeepCyberpunksOrder() {
		SavesModel.Saves s = SavesModel.of(reply(status("ok"), save(0, "a", false, line(1, "A")), save(1, "b", false, line(1, "B")), save(2, "c", false,
			line(1, "C"))));
		assertEquals(List.of("A", "B", "C"), s.list().stream().map(SavesModel.Save::key).toList());
	}

	@Test
	void anUnavailableListSaysWhy() {
		SavesModel.Saves s = SavesModel.of(reply(status("unavailable: RequestSavesForLoad gave no answer in 3 s")));
		assertFalse(s.available());
		assertEquals("RequestSavesForLoad gave no answer in 3 s", s.why());
		assertTrue(s.list().isEmpty());
	}

	@Test
	void aReplyThatIsNoSaveListIsUnavailable() {
		// the fake host's sample journal (a job with objectives first) or an empty reply
		assertFalse(SavesModel.of(reply(save(123, "The Heist", false, line(5, "Get in")))).available());
		assertFalse(SavesModel.of(reply()).available());
	}

	@Test
	void damagedAndOverlongSavesAreNotLoadable() {
		SavesModel.Saves s = SavesModel.of(reply(status("ok"), save(0, "Bad", true, line(1, "ManualSave-1")), save(1, "Long", false, line(1, "x".repeat(65))),
			save(2, "NoKey", false)));
		assertTrue(s.list().stream().noneMatch(SavesModel.Save::loadable));
		assertTrue(SavesModel.fitsArg("x".repeat(64)));
		assertFalse(SavesModel.fitsArg("é".repeat(33)), "66 UTF-8 bytes");
	}

	@Test
	void badMetadataFieldsAreUnknownNotFatal() {
		SavesModel.Saves s = SavesModel.of(reply(status("ok"), save(0, "W", false, line(1, "K"), line(2, "ManualSave|-3|abc"))));
		SavesModel.Save save = s.list().getFirst();
		assertEquals(-1, save.level());
		assertEquals(-1L, save.played());
		assertNull(save.time());
		assertEquals("", save.lifePath());
	}

	@Test
	void timestampsInEveryLikelyUnitAndNoneOther() {
		Instant t = Instant.ofEpochSecond(1_759_000_000L);
		assertEquals(t, SavesModel.time("1759000000"));
		assertEquals(t, SavesModel.time("1759000000123"));
		assertEquals(t, SavesModel.time(Long.toString((1_759_000_000L + 11_644_473_600L) * 10_000_000L)));
		assertNull(SavesModel.time("42"));
		assertNull(SavesModel.time("18446744073709551615"));
		assertNull(SavesModel.time("soon"));
	}

	@Test
	void playTime() {
		assertEquals("", SavesModel.played(-1));
		assertEquals("5m", SavesModel.played(300));
		assertEquals("12h 05m", SavesModel.played(12 * 3600 + 5 * 60 + 59));
	}
}
