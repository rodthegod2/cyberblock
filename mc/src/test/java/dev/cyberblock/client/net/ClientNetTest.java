package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ClientNetTest {
	/** Section 1.3: DOWN reaches the DLL only when this client is the host and its DLL is linked. */
	@Test
	void downIsForwardedOnlyForTheLinkedHost() {
		assertTrue(ClientNet.forwardsDown(ClientNet.Role.HOST, true));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.HOST, false));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.GUEST, true));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.NONE, true));
	}

	/** Section 1.2: the client's own LOCAL messages never come from the server; a guest (or unlinked host) gets no survey_cmd either. */
	@Test
	void clientOnlyTypesAreNotAcceptedFromTheServer() {
		ClientNet.Role host = ClientNet.Role.HOST;
		for (String t : new String[] {"hello", "status", "binds", "uiact", "persp", "flight", "survey", "session", "prefs"}) {
			assertEquals(ClientNet.DownAction.DROP, ClientNet.downAction(host, true, t), t);
		}

		assertEquals(ClientNet.DownAction.DROP, ClientNet.downAction(host, true, null));
		assertEquals(ClientNet.DownAction.FORWARD, ClientNet.downAction(host, true, "blocks"));
		assertEquals(ClientNet.DownAction.FORWARD, ClientNet.downAction(host, true, "align"));
		assertEquals(ClientNet.DownAction.FORWARD, ClientNet.downAction(host, true, "some_later_phase_type"), "a denylist: later DOWN types pass");

		assertEquals(ClientNet.DownAction.SURVEY, ClientNet.downAction(host, true, "survey_cmd"));
		assertEquals(ClientNet.DownAction.IGNORE, ClientNet.downAction(ClientNet.Role.GUEST, true, "survey_cmd"), "a refused or guest DLL");
		assertEquals(ClientNet.DownAction.IGNORE, ClientNet.downAction(ClientNet.Role.NONE, true, "survey_cmd"));
		assertEquals(ClientNet.DownAction.IGNORE, ClientNet.downAction(host, false, "survey_cmd"), "no DLL linked");
		assertEquals(ClientNet.DownAction.IGNORE, ClientNet.downAction(ClientNet.Role.GUEST, true, "uiact"));
	}

	/** Section 1.4: the DLL's hello goes UP once per host grant and once per changed hello, not on a perms hs_ack. */
	@Test
	void theHelloIsRelayedOncePerGrant() {
		assertTrue(ClientNet.helloPending(false, ClientNet.Role.NONE, ClientNet.Role.HOST), "first grant");
		assertTrue(ClientNet.helloPending(false, ClientNet.Role.GUEST, ClientNet.Role.HOST), "guest promoted");
		assertFalse(ClientNet.helloPending(false, ClientNet.Role.HOST, ClientNet.Role.HOST), "perms toggle: no second hello");
		assertTrue(ClientNet.helloPending(true, ClientNet.Role.HOST, ClientNet.Role.HOST), "the DLL's ver changed while host");
		assertFalse(ClientNet.helloPending(true, ClientNet.Role.HOST, ClientNet.Role.GUEST), "left the host role: cleared");
		assertFalse(ClientNet.helloPending(true, ClientNet.Role.NONE, ClientNet.Role.NONE), "refused: cleared");
	}

	@Test
	void theDownTypeIsReadFromTheFront() {
		assertEquals("blocks", ClientNet.type("{\"t\":\"blocks\",\"reset\":true,\"c\":[" + "1,".repeat(1000) + "1]}"));
		assertEquals("survey_cmd", ClientNet.type("{\"t\":\"survey_cmd\",\"op\":\"start\"}"));
		assertEquals("align", ClientNet.type("{\"k\":3,\"t\":\"align\"}"), "t not first: a full parse");
		assertNull(ClientNet.type("not json"));
	}

	/** Section 1.5: the session state the DLL gets after a handshake result. */
	@Test
	void sessionStateFollowsTheHandshake() {
		assertEquals("host", ClientNet.sessionState(true, "host", ""));
		assertEquals("guest", ClientNet.sessionState(true, "guest", ""));
		assertEquals("refused", ClientNet.sessionState(true, "guest", "Cyberblock: your Cyberpunk plugin is 0.9 but your mod is 1.0"));
		assertEquals("refused", ClientNet.sessionState(false, "guest", "version mismatch"));
	}

	@Test
	void sessionMessageHasTheContractShape() {
		JsonObject m = JsonParser.parseString(ClientNet.sessionMessage("host", "", 1 | 4 | 16)).getAsJsonObject();
		assertEquals("session", m.get("t").getAsString());
		assertEquals("host", m.get("state").getAsString());
		assertEquals("", m.get("reason").getAsString());
		JsonObject p = m.getAsJsonObject("perms");
		assertTrue(p.get("explosions").getAsBoolean());
		assertFalse(p.get("damage").getAsBoolean());
		assertTrue(p.get("hacks").getAsBoolean());
		assertFalse(p.get("devices").getAsBoolean());
		assertTrue(p.get("spawns").getAsBoolean());
	}

	@Test
	void theSessionReasonIsCutTo128Bytes() {
		String reason = JsonParser.parseString(ClientNet.sessionMessage("refused", "é".repeat(100), 0)).getAsJsonObject().get("reason").getAsString();
		assertEquals(64, reason.length(), "two bytes each");
		assertEquals("abc", ClientNet.truncate("abc", 128));
		assertEquals("😀", ClientNet.truncate("😀😀", 5), "never half a surrogate pair");
	}
}
