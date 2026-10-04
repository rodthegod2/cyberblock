package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterTest {
	@Test
	void theRosterIsReplacedWholeAndCleared() {
		UUID host = UUID.randomUUID(), guest = UUID.randomUUID();
		Roster.set(List.of(new Roster.Entry(host, "CbHost1", true, "male", -1, false, false, false),
			new Roster.Entry(guest, "CbGuest1", false, "female", 2, true, false, false)));
		assertTrue(Roster.linked(host));
		assertFalse(Roster.linked(guest));
		assertEquals(2, Roster.of(guest).team());
		assertEquals(2, Roster.all().size());
		Roster.set(List.of(new Roster.Entry(guest, "CbGuest1", false, "female", -1, false, true, false)));
		assertNull(Roster.of(host), "gone from the newest list");
		assertTrue(Roster.of(guest).downed());
		Roster.clear();
		assertTrue(Roster.all().isEmpty());
		assertFalse(Roster.linked(guest));
	}
}
