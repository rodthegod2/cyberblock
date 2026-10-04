package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Megaplan 2026-10-04 task 1-C1: scans and hacks go to the target's owner (guests too), perks and cyberware only to the owning host. */
class CyberRouteTest {
	/** A host session as the route sees it: its own request ids, its `hacks` toggle, and what went DOWN to its Cyberpunk. */
	private static final class FakeOwner implements CyberRoute.Owner {
		final UUID uuid = UUID.randomUUID();
		final List<String> down = new ArrayList<>();
		final Map<Integer, UUID> askers = new HashMap<>();
		boolean guestHacks = true;
		int next;

		@Override
		public UUID uuid() {
			return this.uuid;
		}

		@Override
		public boolean active() {
			return true;
		}

		@Override
		public boolean allowsHacks(final UUID guest) {
			return this.guestHacks;
		}

		@Override
		public int newRequest(final UUID asker) {
			int id = this.next++;
			this.askers.put(id, asker);
			return id;
		}

		@Override
		public @Nullable UUID asker(final int id) {
			return this.askers.get(id);
		}

		@Override
		public void send(final String json) {
			this.down.add(json);
		}

		JsonObject last() {
			return JsonParser.parseString(this.down.getLast()).getAsJsonObject();
		}
	}

	private final FakeOwner host = new FakeOwner();
	private final FakeOwner other = new FakeOwner();
	private final UUID guest = UUID.randomUUID();

	@Test
	void aGuestScanGoesToTheOwnerOfTheTarget() {
		CyberRoute.Sent sent = CyberRoute.scan(this.guest, this.host, 77L, null);
		assertTrue(sent.ok());
		assertSame(this.host, sent.owner());
		assertEquals("scan", this.host.last().get("t").getAsString());
		assertEquals("77", this.host.last().get("target").getAsString());
		assertEquals(sent.id(), this.host.last().get("id").getAsInt());
		// the owner's session routes the answer back to the guest, and nobody else heard of it
		assertEquals(this.guest, this.host.asker(sent.id()));
		assertTrue(this.other.down.isEmpty());
		assertEquals(new CyberRoute.Key(this.host.uuid, sent.id()), sent.key());
	}

	@Test
	void aHostScanOfAnotherHostsNpcGoesToThatHost() {
		CyberRoute.Sent sent = CyberRoute.scan(this.host.uuid, this.other, 5L, this.host);
		assertSame(this.other, sent.owner());
		assertEquals(1, this.other.down.size());
		assertTrue(this.host.down.isEmpty());
	}

	@Test
	void theHacksToggleRefusesGuestsButNeverTheHost() {
		this.host.guestHacks = false;
		CyberRoute.Sent sent = CyberRoute.scan(this.guest, this.host, 77L, null);
		assertEquals(CyberRoute.Refusal.BLOCKED, sent.refusal());
		assertNull(sent.key());
		assertEquals(CyberRoute.Refusal.BLOCKED, CyberRoute.hack(this.guest, this.host, 77L, Hack.PING, false).refusal());
		assertTrue(this.host.down.isEmpty(), "nothing reaches the host's game");
		assertTrue(CyberRoute.scan(this.host.uuid, this.host, 77L, this.host).ok(), "the host's own scan always goes");
		assertTrue(CyberRoute.hack(this.host.uuid, this.host, 77L, Hack.PING, false).ok());
	}

	/** `target:null` (Cyberpunk looks at what V aims at) only for the host's own game. */
	@Test
	void nothingUnderTheRayGoesOnlyToTheAskersOwnGame() {
		CyberRoute.Sent own = CyberRoute.scan(this.host.uuid, null, 0L, this.host);
		assertTrue(own.ok());
		assertTrue(this.host.last().get("target").isJsonNull());
		assertEquals(CyberRoute.Refusal.NO_HOST, CyberRoute.scan(this.guest, null, 0L, null).refusal());
		assertEquals(1, this.host.down.size());
	}

	@Test
	void aGuestHackGoesToTheOwnerWithItsRequestId() {
		CyberRoute.Sent sent = CyberRoute.hack(this.guest, this.host, 9L, Hack.SHORT_CIRCUIT, true);
		JsonObject m = this.host.last();
		assertEquals("hack", m.get("t").getAsString());
		assertEquals(sent.id(), m.get("id").getAsInt());
		assertEquals("9", m.get("target").getAsString());
		assertTrue(m.get("chain").getAsBoolean());
		assertEquals(this.guest, this.host.asker(sent.id()));
	}

	/** Two hosts issue the same ids: a request id only means something together with its owner. */
	@Test
	void requestIdsAreScopedByOwner() {
		CyberRoute.Sent a = CyberRoute.hack(this.guest, this.host, 1L, Hack.PING, false);
		CyberRoute.Sent b = CyberRoute.hack(this.guest, this.other, 1L, Hack.PING, false);
		assertEquals(a.id(), b.id());
		assertNotEquals(a.key(), b.key());
		assertNull(this.other.asker(a.id() + 1), "an id the session never issued routes nowhere");
	}

	@Test
	void perksAndCyberwareGoOnlyToTheOwningHost() {
		String perks = CyberWire.perks(Map.of("iron_skin", 1));
		assertTrue(CyberRoute.toOwn(this.host, perks));
		assertEquals(List.of(perks), this.host.down);
		assertTrue(this.other.down.isEmpty());
		// a guest has no session: his perks and implants stay Minecraft-side
		assertFalse(CyberRoute.toOwn(null, CyberWire.cyberware(Set.of("mantis_blades"))));
		assertEquals(1, this.host.down.size());
	}
}
